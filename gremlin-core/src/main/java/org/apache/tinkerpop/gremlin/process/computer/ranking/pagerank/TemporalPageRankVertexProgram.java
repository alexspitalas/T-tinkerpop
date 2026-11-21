/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.computer.ranking.pagerank;

import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.computer.Memory;
import org.apache.tinkerpop.gremlin.process.computer.MemoryComputeKey;
import org.apache.tinkerpop.gremlin.process.computer.MessageScope;
import org.apache.tinkerpop.gremlin.process.computer.Messenger;
import org.apache.tinkerpop.gremlin.process.computer.VertexComputeKey;
import org.apache.tinkerpop.gremlin.process.computer.VertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.traversal.TraversalVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.util.AbstractVertexProgramBuilder;
import org.apache.tinkerpop.gremlin.process.ranking.pagerank.TemporalPageRankAlgorithm;
import org.apache.tinkerpop.gremlin.process.traversal.Operator;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.IndexedTraverserSet;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceFactory;
import org.apache.tinkerpop.gremlin.structure.util.star.StarGraph;
import org.javatuples.Pair;
import org.javatuples.Triplet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Distributed BSP {@link VertexProgram} implementation of Rozenshtein-Gionis Temporal PageRank.
 * <p>
 * Executes in chronological epoch order over all scoped vertices and temporal edges using O(1) driver
 * {@link Memory} (streaming the next minimum epoch via {@link Operator#min}) and Gryo/Java-serializable
 * {@link Triplet}/{@link Pair} payloads. When {@code bucketMillis == 0} (default), every distinct edge start
 * timestamp runs in its own superstep, matching single-JVM {@link TemporalPageRankAlgorithm} semantics.
 */
public class TemporalPageRankVertexProgram implements VertexProgram<Triplet<Object, Object, Object>> {

    public static final String PAGE_RANK = "gremlin.temporalPageRank.pageRank";

    public static final String ALPHA = "gremlin.temporalPageRankVertexProgram.alpha";
    public static final String BETA = "gremlin.temporalPageRankVertexProgram.beta";
    public static final String PROPERTY = "gremlin.temporalPageRankVertexProgram.property";
    public static final String NORMALIZE = "gremlin.temporalPageRankVertexProgram.normalize";
    public static final String BUCKET_MILLIS = "gremlin.temporalPageRankVertexProgram.bucketMillis";
    public static final String ACTIVE_WINDOW = "gremlin.temporalPageRankVertexProgram.activeWindow";
    public static final String SCOPED_BY_HALTED_TRAVERSERS = "gremlin.temporalPageRankVertexProgram.scopedByHaltedTraversers";

    private static final String PHASE = "gremlin.temporalPageRankVertexProgram.phase";
    private static final String CURRENT_EPOCH = "gremlin.temporalPageRankVertexProgram.currentEpoch";
    private static final String NEXT_EPOCH = "gremlin.temporalPageRankVertexProgram.nextEpoch";
    private static final String HAS_NEXT_EPOCH = "gremlin.temporalPageRankVertexProgram.hasNextEpoch";
    private static final String TOTAL_RANK = "gremlin.temporalPageRankVertexProgram.totalRank";

    private static final String STATE_SCOPED = "gremlin.temporalPageRankVertexProgram.state.scoped";
    private static final String STATE_RANK = "gremlin.temporalPageRankVertexProgram.state.rank";
    private static final String STATE_ACTIVE_MASS = "gremlin.temporalPageRankVertexProgram.state.activeMass";
    private static final String STATE_HEAP = "gremlin.temporalPageRankVertexProgram.state.heap";
    private static final String STATE_TRANSITIONS = "gremlin.temporalPageRankVertexProgram.state.transitions";
    private static final String STATE_CURSOR = "gremlin.temporalPageRankVertexProgram.state.cursor";

    private static final int PHASE_EXCHANGE_LIFETIMES = 0;
    private static final int PHASE_BUILD_TRANSITIONS = 1;
    private static final int PHASE_RUN_EPOCH = 2;
    private static final int PHASE_FINALIZE = 3;
    private static final int PHASE_NORMALIZE = 4;

    private double alpha = TemporalPageRankAlgorithm.DEFAULT_ALPHA;
    private double beta = TemporalPageRankAlgorithm.DEFAULT_BETA;
    private String property = PAGE_RANK;
    private boolean normalize = TemporalPageRankAlgorithm.DEFAULT_NORMALIZE;
    private long bucketMillis = 0L;
    private Lifetime activeWindow = null;
    private boolean scopedByHaltedTraversers = false;
    private TraverserSet<Vertex> haltedTraversers = null;
    private IndexedTraverserSet<Vertex, Vertex> haltedTraversersIndex = null;
    private Set<Object> haltedTraverserVertexIds = null;

    private Set<VertexComputeKey> vertexComputeKeys;
    private final Set<MemoryComputeKey> memoryComputeKeys = new HashSet<>(Arrays.asList(
            MemoryComputeKey.of(PHASE, Operator.assign, true, true),
            MemoryComputeKey.of(CURRENT_EPOCH, Operator.assign, true, true),
            MemoryComputeKey.of(NEXT_EPOCH, Operator.min, false, true),
            MemoryComputeKey.of(HAS_NEXT_EPOCH, Operator.or, false, true),
            MemoryComputeKey.of(TOTAL_RANK, Operator.sum, true, true)));

    private TemporalPageRankVertexProgram() {
    }

    @Override
    public void loadState(final Graph graph, final Configuration configuration) {
        this.alpha = configuration.getDouble(ALPHA, TemporalPageRankAlgorithm.DEFAULT_ALPHA);
        this.beta = configuration.getDouble(BETA, TemporalPageRankAlgorithm.DEFAULT_BETA);
        this.property = configuration.getString(PROPERTY, PAGE_RANK);
        this.normalize = configuration.getBoolean(NORMALIZE, TemporalPageRankAlgorithm.DEFAULT_NORMALIZE);
        this.bucketMillis = configuration.getLong(BUCKET_MILLIS, 0L);
        this.scopedByHaltedTraversers = configuration.getBoolean(SCOPED_BY_HALTED_TRAVERSERS, false);

        if (configuration.containsKey(ACTIVE_WINDOW)) {
            final Object raw = configuration.getProperty(ACTIVE_WINDOW);
            if (raw == null) {
                this.activeWindow = null;
            } else if (raw instanceof Lifetime) {
                this.activeWindow = (Lifetime) raw;
            } else {
                final String encoded;
                if (raw instanceof Collection) {
                    final StringBuilder sb = new StringBuilder();
                    for (final Object part : (Collection<?>) raw) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(part);
                    }
                    encoded = sb.toString();
                } else {
                    encoded = raw.toString();
                }
                this.activeWindow = encoded.isEmpty() ? Lifetime.EMPTY : Lifetime.fromTemporalIntervals(encoded);
            }
        } else {
            this.activeWindow = null;
        }

        validateConfig();

        this.haltedTraversers = TraversalVertexProgram.loadHaltedTraversers(configuration);
        if (this.haltedTraversers != null && !this.haltedTraversers.isEmpty()) {
            this.haltedTraverserVertexIds = new HashSet<>();
            this.haltedTraversersIndex = new IndexedTraverserSet<>(v -> v);
            for (final Traverser.Admin<Vertex> traverser : this.haltedTraversers) {
                final Object obj = traverser.get();
                if (!(obj instanceof Vertex)) {
                    throw new IllegalArgumentException("temporalPageRank() can only be applied to vertex traversers");
                }
                this.haltedTraverserVertexIds.add(((Vertex) obj).id());
                this.haltedTraversersIndex.add(traverser.split());
            }
        } else {
            this.haltedTraverserVertexIds = null;
            this.haltedTraversersIndex = null;
        }

        this.vertexComputeKeys = new HashSet<>(Arrays.asList(
                VertexComputeKey.of(this.property, false),
                VertexComputeKey.of(STATE_SCOPED, true),
                VertexComputeKey.of(STATE_RANK, true),
                VertexComputeKey.of(STATE_ACTIVE_MASS, true),
                VertexComputeKey.of(STATE_HEAP, true),
                VertexComputeKey.of(STATE_TRANSITIONS, true),
                VertexComputeKey.of(STATE_CURSOR, true)));
        if (this.haltedTraversers != null && !this.haltedTraversers.isEmpty()) {
            this.vertexComputeKeys.add(VertexComputeKey.of(TraversalVertexProgram.HALTED_TRAVERSERS, false));
        }
    }

    @Override
    public void storeState(final Configuration configuration) {
        VertexProgram.super.storeState(configuration);
        configuration.setProperty(ALPHA, this.alpha);
        configuration.setProperty(BETA, this.beta);
        configuration.setProperty(PROPERTY, this.property);
        configuration.setProperty(NORMALIZE, this.normalize);
        configuration.setProperty(BUCKET_MILLIS, this.bucketMillis);
        configuration.setProperty(SCOPED_BY_HALTED_TRAVERSERS, this.scopedByHaltedTraversers);
        if (this.activeWindow != null) {
            configuration.setProperty(ACTIVE_WINDOW, this.activeWindow.toTemporalIntervalsString());
        }
        if (this.haltedTraversers != null && !this.haltedTraversers.isEmpty()) {
            TraversalVertexProgram.storeHaltedTraversers(configuration, this.haltedTraversers);
        }
    }

    private void validateConfig() {
        if (!(this.alpha > 0.0d && this.alpha < 1.0d))
            throw new IllegalArgumentException("Temporal PageRank alpha must be greater than 0.0 and less than 1.0");
        if (!(this.beta > 0.0d && this.beta <= 1.0d))
            throw new IllegalArgumentException("Temporal PageRank beta must be greater than 0.0 and less than or equal to 1.0");
        if (this.property == null || this.property.trim().isEmpty())
            throw new IllegalArgumentException("Temporal PageRank propertyName must be a non-empty String");
        if (this.bucketMillis < 0L)
            throw new IllegalArgumentException("Temporal PageRank bucketMillis must be non-negative");
    }

    @Override
    public GraphComputer.ResultGraph getPreferredResultGraph() {
        return GraphComputer.ResultGraph.NEW;
    }

    @Override
    public GraphComputer.Persist getPreferredPersist() {
        return GraphComputer.Persist.VERTEX_PROPERTIES;
    }

    @Override
    public Set<VertexComputeKey> getVertexComputeKeys() {
        return this.vertexComputeKeys;
    }

    @Override
    public Set<MemoryComputeKey> getMemoryComputeKeys() {
        return this.memoryComputeKeys;
    }

    @Override
    public Set<MessageScope> getMessageScopes(final Memory memory) {
        return Collections.emptySet();
    }

    @Override
    public TemporalPageRankVertexProgram clone() {
        try {
            return (TemporalPageRankVertexProgram) super.clone();
        } catch (final CloneNotSupportedException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void setup(final Memory memory) {
        memory.set(PHASE, PHASE_EXCHANGE_LIFETIMES);
        memory.set(CURRENT_EPOCH, Long.MIN_VALUE);
        memory.set(NEXT_EPOCH, Long.MAX_VALUE);
        memory.set(HAS_NEXT_EPOCH, false);
        memory.set(TOTAL_RANK, 0.0d);
    }

    @Override
    public void execute(final Vertex vertex, final Messenger<Triplet<Object, Object, Object>> messenger, final Memory memory) {
        final int phase = memory.get(PHASE);
        switch (phase) {
            case PHASE_EXCHANGE_LIFETIMES:
                executeExchangeLifetimes(vertex, messenger);
                break;
            case PHASE_BUILD_TRANSITIONS:
                executeBuildTransitions(vertex, messenger, memory);
                break;
            case PHASE_RUN_EPOCH:
                executeRunEpoch(vertex, messenger, memory);
                break;
            case PHASE_FINALIZE:
                executeFinalize(vertex, messenger, memory);
                break;
            case PHASE_NORMALIZE:
                executeNormalize(vertex, memory);
                break;
            default:
                throw new IllegalStateException("Unexpected TemporalPageRankVertexProgram phase: " + phase);
        }
    }

    @Override
    public boolean terminate(final Memory memory) {
        final int phase = memory.get(PHASE);
        switch (phase) {
            case PHASE_EXCHANGE_LIFETIMES:
                if (this.haltedTraversersIndex != null) {
                    this.haltedTraversersIndex.clear();
                }
                memory.set(PHASE, PHASE_BUILD_TRANSITIONS);
                return false;
            case PHASE_BUILD_TRANSITIONS:
            case PHASE_RUN_EPOCH:
                final boolean hasNext = Boolean.TRUE.equals(memory.<Boolean>get(HAS_NEXT_EPOCH));
                if (hasNext) {
                    final long nextEpoch = memory.get(NEXT_EPOCH);
                    memory.set(CURRENT_EPOCH, nextEpoch);
                    memory.set(NEXT_EPOCH, Long.MAX_VALUE);
                    memory.set(HAS_NEXT_EPOCH, false);
                    memory.set(PHASE, PHASE_RUN_EPOCH);
                } else {
                    memory.set(PHASE, PHASE_FINALIZE);
                }
                return false;
            case PHASE_FINALIZE:
                if (!this.normalize) {
                    return true;
                }
                memory.set(PHASE, PHASE_NORMALIZE);
                return false;
            case PHASE_NORMALIZE:
                return true;
            default:
                return true;
        }
    }

    private void executeExchangeLifetimes(final Vertex vertex, final Messenger<Triplet<Object, Object, Object>> messenger) {
        copyHaltedTraversersFromMemory(vertex);
        final boolean scoped = isScopedVertex(vertex);
        vertex.property(VertexProperty.Cardinality.single, STATE_SCOPED, scoped);
        if (!scoped) {
            return;
        }

        final Lifetime effectiveVertexLifetime = resolveVertexEffectiveLifetime(vertex);

        final Object selfId = vertex.id();
        final Set<Object> notifiedOutIds = new HashSet<>();
        final Iterator<Edge> inEdges = vertex.edges(Direction.IN);
        try {
            while (inEdges.hasNext()) {
                final Edge edge = inEdges.next();
                final Vertex outV = edge.outVertex();
                final Object outId = outV.id();
                if (!selfId.equals(outId) && notifiedOutIds.add(outId)) {
                    messenger.sendMessage(MessageScope.Global.of(ReferenceFactory.detach(outV)),
                            Triplet.with(selfId, effectiveVertexLifetime, null));
                }
            }
        } finally {
            CloseableIterator.closeIterator(inEdges);
        }
    }

    private void executeBuildTransitions(final Vertex vertex, final Messenger<Triplet<Object, Object, Object>> messenger, final Memory memory) {
        final boolean scoped = vertex.<Boolean>property(STATE_SCOPED).orElse(false);
        if (!scoped) {
            return;
        }

        final Map<Object, Lifetime> targetLifetimes = new HashMap<>();
        final Iterator<Triplet<Object, Object, Object>> msgs = messenger.receiveMessages();
        while (msgs.hasNext()) {
            final Triplet<Object, Object, Object> msg = msgs.next();
            targetLifetimes.put(msg.getValue0(), (Lifetime) msg.getValue1());
        }

        final Lifetime vLt = resolveVertexEffectiveLifetime(vertex);
        final List<RawTransition> rawTransitions = new ArrayList<>();
        if (!vLt.isEmpty()) {
            final Object selfId = vertex.id();
            final Iterator<Edge> outEdges = vertex.edges(Direction.OUT);
            try {
                while (outEdges.hasNext()) {
                    final Edge edge = outEdges.next();
                    final Vertex inV = edge.inVertex();
                    final Object inId = inV.id();
                    Lifetime inLt = selfId.equals(inId) ? vLt : targetLifetimes.get(inId);
                    if (inLt == null && isTargetScopedVertex(inV)) {
                        inLt = resolveVertexEffectiveLifetime(inV);
                    }
                    if (inLt == null || inLt.isEmpty()) {
                        continue;
                    }
                    Lifetime effective = Lifetime.getLifetimeFromProperties(edge).intersection(vLt);
                    if (effective.isEmpty()) {
                        continue;
                    }
                    if (!selfId.equals(inId)) {
                        effective = effective.intersection(inLt);
                        if (effective.isEmpty()) {
                            continue;
                        }
                    }
                    final Vertex detachedInV = ReferenceFactory.detach(inV);
                    final Object edgeId = edge.id();
                    final int intervalCount = effective.getIntervalCount();
                    for (int i = 0; i < intervalCount; i++) {
                        final long sMs = effective.getStartMillis(i);
                        final long eMs = effective.getEndMillis(i);
                        rawTransitions.add(new RawTransition(epochOf(sMs), sMs, eMs, edgeId, detachedInV));
                    }
                }
            } finally {
                CloseableIterator.closeIterator(outEdges);
            }
        }

        rawTransitions.sort(TemporalPageRankVertexProgram::compareRawTransitions);

        final ArrayList<Triplet<Long, Long, Vertex>> compactTransitions = new ArrayList<>(rawTransitions.size());
        for (final RawTransition rt : rawTransitions) {
            compactTransitions.add(Triplet.with(rt.startMs, rt.endMs, rt.targetVertex));
        }

        vertex.property(VertexProperty.Cardinality.single, STATE_RANK, 0.0d);
        vertex.property(VertexProperty.Cardinality.single, STATE_ACTIVE_MASS, 0.0d);
        vertex.property(VertexProperty.Cardinality.single, STATE_HEAP, new ArrayList<Pair<Long, Double>>());
        vertex.property(VertexProperty.Cardinality.single, STATE_TRANSITIONS, compactTransitions);
        vertex.property(VertexProperty.Cardinality.single, STATE_CURSOR, 0);

        if (!compactTransitions.isEmpty()) {
            memory.add(NEXT_EPOCH, epochOf(compactTransitions.get(0).getValue0()));
            memory.add(HAS_NEXT_EPOCH, true);
        }
    }

    private void executeRunEpoch(final Vertex vertex, final Messenger<Triplet<Object, Object, Object>> messenger, final Memory memory) {
        final boolean scoped = vertex.<Boolean>property(STATE_SCOPED).orElse(false);
        if (!scoped) {
            return;
        }

        final Iterator<Triplet<Object, Object, Object>> msgs = messenger.receiveMessages();
        final boolean hasIncoming = msgs.hasNext();
        final ArrayList<Triplet<Long, Long, Vertex>> transitions =
                vertex.<ArrayList<Triplet<Long, Long, Vertex>>>property(STATE_TRANSITIONS).orElse(null);
        int cursor = vertex.<Integer>property(STATE_CURSOR).orElse(0);
        final long currentEpoch = memory.get(CURRENT_EPOCH);
        final boolean hasActiveTransitions = transitions != null && cursor < transitions.size()
                && epochOf(transitions.get(cursor).getValue0()) == currentEpoch;

        // Zero-allocation fast path for vertices idle at this epoch
        if (!hasIncoming && !hasActiveTransitions) {
            if (transitions != null && cursor < transitions.size()) {
                memory.add(NEXT_EPOCH, epochOf(transitions.get(cursor).getValue0()));
                memory.add(HAS_NEXT_EPOCH, true);
            }
            return;
        }

        double r = vertex.<Double>property(STATE_RANK).orElse(0.0d);
        double s = vertex.<Double>property(STATE_ACTIVE_MASS).orElse(0.0d);
        final ArrayList<Pair<Long, Double>> heap = new ArrayList<>(
                vertex.<ArrayList<Pair<Long, Double>>>property(STATE_HEAP).orElseGet(ArrayList::new));

        if (hasIncoming) {
            while (msgs.hasNext()) {
                final Triplet<Object, Object, Object> msg = msgs.next();
                final double rankDelta = ((Number) msg.getValue0()).doubleValue();
                final double schedMass = ((Number) msg.getValue1()).doubleValue();
                final long availableAt = ((Number) msg.getValue2()).longValue();
                r += rankDelta;
                if (schedMass != 0.0d) {
                    heapPush(heap, availableAt, schedMass);
                }
            }
        }

        if (hasActiveTransitions) {
            final double injected = 1.0d - this.alpha;
            final boolean betaIsOne = Double.compare(this.beta, 1.0d) == 0;
            final double oneMinusBetaAlpha = (1.0d - this.beta) * this.alpha;
            final Object selfId = vertex.id();

            Map<Pair<Vertex, Long>, double[]> pendingMessages = null;
            List<Pair<Long, Double>> deferredSelfMass = null;

            while (cursor < transitions.size() && epochOf(transitions.get(cursor).getValue0()) == currentEpoch) {
                final Triplet<Long, Long, Vertex> t = transitions.get(cursor);
                final long startMs = t.getValue0();
                final long endMs = t.getValue1();
                final Vertex targetV = t.getValue2();

                s += heapRelease(heap, startMs);
                r += injected;
                s += injected;

                final double activeSourceMass = s;
                final double rankDelta = activeSourceMass * this.alpha;
                final double schedMass = betaIsOne ? activeSourceMass * this.alpha : activeSourceMass * oneMinusBetaAlpha;
                s = betaIsOne ? 0.0d : activeSourceMass * this.beta;

                if (selfId.equals(targetV.id())) {
                    r += rankDelta;
                    if (schedMass != 0.0d) {
                        if (deferredSelfMass == null) {
                            deferredSelfMass = new ArrayList<>(2);
                        }
                        deferredSelfMass.add(Pair.with(endMs, schedMass));
                    }
                } else {
                    if (pendingMessages == null) {
                        pendingMessages = new LinkedHashMap<>();
                    }
                    final Pair<Vertex, Long> key = Pair.with(targetV, endMs);
                    final double[] acc = pendingMessages.computeIfAbsent(key, k -> new double[2]);
                    acc[0] += rankDelta;
                    acc[1] += schedMass;
                }
                cursor++;
            }

            if (deferredSelfMass != null) {
                for (final Pair<Long, Double> deferred : deferredSelfMass) {
                    heapPush(heap, deferred.getValue0(), deferred.getValue1());
                }
            }

            if (pendingMessages != null) {
                for (final Map.Entry<Pair<Vertex, Long>, double[]> entry : pendingMessages.entrySet()) {
                    final Vertex targetV = entry.getKey().getValue0();
                    final long endMs = entry.getKey().getValue1();
                    final double[] acc = entry.getValue();
                    messenger.sendMessage(MessageScope.Global.of(targetV),
                            Triplet.with(acc[0], acc[1], endMs));
                }
            }

            vertex.property(VertexProperty.Cardinality.single, STATE_CURSOR, cursor);
        }

        if (transitions != null && cursor < transitions.size()) {
            memory.add(NEXT_EPOCH, epochOf(transitions.get(cursor).getValue0()));
            memory.add(HAS_NEXT_EPOCH, true);
        }

        vertex.property(VertexProperty.Cardinality.single, STATE_RANK, r);
        vertex.property(VertexProperty.Cardinality.single, STATE_ACTIVE_MASS, s);
        vertex.property(VertexProperty.Cardinality.single, STATE_HEAP, heap);
    }

    private void executeFinalize(final Vertex vertex, final Messenger<Triplet<Object, Object, Object>> messenger, final Memory memory) {
        final boolean scoped = vertex.<Boolean>property(STATE_SCOPED).orElse(false);
        if (!scoped) {
            return;
        }

        double r = vertex.<Double>property(STATE_RANK).orElse(0.0d);
        final Iterator<Triplet<Object, Object, Object>> msgs = messenger.receiveMessages();
        while (msgs.hasNext()) {
            final Triplet<Object, Object, Object> msg = msgs.next();
            r += ((Number) msg.getValue0()).doubleValue();
        }

        if (this.normalize) {
            vertex.property(VertexProperty.Cardinality.single, STATE_RANK, r);
            memory.add(TOTAL_RANK, r);
        } else {
            vertex.property(VertexProperty.Cardinality.single, this.property, r);
        }
    }

    private void executeNormalize(final Vertex vertex, final Memory memory) {
        final boolean scoped = vertex.<Boolean>property(STATE_SCOPED).orElse(false);
        if (!scoped) {
            return;
        }

        final double r = vertex.<Double>property(STATE_RANK).orElse(0.0d);
        final double total = memory.<Double>get(TOTAL_RANK);
        final double normalized = total != 0.0d ? r / total : 0.0d;
        vertex.property(VertexProperty.Cardinality.single, this.property, normalized);
    }

    private void copyHaltedTraversersFromMemory(final Vertex vertex) {
        if (this.haltedTraversersIndex == null) {
            return;
        }
        final Collection<Traverser.Admin<Vertex>> traversers = this.haltedTraversersIndex.get(vertex);
        if (traversers != null && !traversers.isEmpty()) {
            final TraverserSet<Vertex> newHaltedTraversers = new TraverserSet<>();
            newHaltedTraversers.addAll(traversers);
            vertex.property(VertexProperty.Cardinality.single, TraversalVertexProgram.HALTED_TRAVERSERS, newHaltedTraversers);
        }
    }

    private boolean isScopedVertex(final Vertex vertex) {
        if (!this.scopedByHaltedTraversers) {
            return true;
        }
        final VertexProperty<TraverserSet<Object>> prop = vertex.property(TraversalVertexProgram.HALTED_TRAVERSERS);
        if (prop.isPresent() && prop.value() != null && !prop.value().isEmpty()) {
            for (final Traverser.Admin<Object> traverser : prop.value()) {
                if (!(traverser.get() instanceof Vertex)) {
                    throw new IllegalArgumentException("temporalPageRank() can only be applied to vertex traversers");
                }
            }
            return true;
        }
        return this.haltedTraverserVertexIds != null && this.haltedTraverserVertexIds.contains(vertex.id());
    }

    private boolean isTargetScopedVertex(final Vertex targetVertex) {
        final Element unwrapped = Lifetime.unwrapElement(targetVertex);
        if (unwrapped instanceof Vertex && !(unwrapped instanceof StarGraph.StarAdjacentVertex)) {
            return ((Vertex) unwrapped).<Boolean>property(STATE_SCOPED).orElse(false);
        }
        return false;
    }

    private Lifetime resolveVertexEffectiveLifetime(final Vertex vertex) {
        final Lifetime propLifetime = Lifetime.getLifetimeFromProperties(vertex);
        return this.activeWindow == null ? propLifetime : propLifetime.intersection(this.activeWindow);
    }

    private long epochOf(final long startMs) {
        return this.bucketMillis > 0L ? Math.floorDiv(startMs, this.bucketMillis) : startMs;
    }

    private static void heapPush(final ArrayList<Pair<Long, Double>> heap, final long availableAt, final double mass) {
        int idx = heap.size();
        heap.add(null);
        while (idx > 0) {
            final int parent = (idx - 1) >>> 1;
            final Pair<Long, Double> parentEntry = heap.get(parent);
            if (parentEntry.getValue0() <= availableAt) {
                break;
            }
            heap.set(idx, parentEntry);
            idx = parent;
        }
        heap.set(idx, Pair.with(availableAt, mass));
    }

    private static double heapRelease(final ArrayList<Pair<Long, Double>> heap, final long startMs) {
        double released = 0.0d;
        while (!heap.isEmpty() && heap.get(0).getValue0() <= startMs) {
            released += heap.get(0).getValue1();
            final int last = heap.size() - 1;
            final Pair<Long, Double> lastEntry = heap.remove(last);
            if (last > 0) {
                final long lastTime = lastEntry.getValue0();
                int idx = 0;
                final int half = last >>> 1;
                while (idx < half) {
                    int child = (idx << 1) + 1;
                    final int right = child + 1;
                    if (right < last && heap.get(right).getValue0() < heap.get(child).getValue0()) {
                        child = right;
                    }
                    if (lastTime <= heap.get(child).getValue0()) {
                        break;
                    }
                    heap.set(idx, heap.get(child));
                    idx = child;
                }
                heap.set(idx, lastEntry);
            }
        }
        return released;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareRawTransitions(final RawTransition t1, final RawTransition t2) {
        int cmp = Long.compare(t1.epochMs, t2.epochMs);
        if (cmp != 0) return cmp;
        cmp = Long.compare(t1.startMs, t2.startMs);
        if (cmp != 0) return cmp;
        final Object id1 = t1.edgeId;
        final Object id2 = t2.edgeId;
        if (id1 == id2) return 0;
        if (id1 instanceof Comparable && id2 instanceof Comparable && id1.getClass().equals(id2.getClass())) {
            return ((Comparable) id1).compareTo(id2);
        }
        return String.valueOf(id1).compareTo(String.valueOf(id2));
    }

    private static final class RawTransition {
        final long epochMs;
        final long startMs;
        final long endMs;
        final Object edgeId;
        final Vertex targetVertex;

        RawTransition(final long epochMs, final long startMs, final long endMs, final Object edgeId, final Vertex targetVertex) {
            this.epochMs = epochMs;
            this.startMs = startMs;
            this.endMs = endMs;
            this.edgeId = edgeId;
            this.targetVertex = targetVertex;
        }
    }

    @Override
    public String toString() {
        return StringFactory.vertexProgramString(this,
                "alpha=" + this.alpha + ", beta=" + this.beta + ", property=" + this.property
                        + ", normalize=" + this.normalize
                        + (this.bucketMillis > 0L ? ", bucketMillis=" + this.bucketMillis : ""));
    }

    @Override
    public Features getFeatures() {
        return new Features() {
            @Override
            public boolean requiresGlobalMessageScopes() {
                return true;
            }

            @Override
            public boolean requiresVertexPropertyAddition() {
                return true;
            }
        };
    }

    public static Builder build() {
        return new Builder();
    }

    public static final class Builder extends AbstractVertexProgramBuilder<Builder> {

        private Builder() {
            super(TemporalPageRankVertexProgram.class);
        }

        public Builder alpha(final double alpha) {
            this.configuration.setProperty(ALPHA, alpha);
            return this;
        }

        public Builder beta(final double beta) {
            this.configuration.setProperty(BETA, beta);
            return this;
        }

        public Builder property(final String property) {
            this.configuration.setProperty(PROPERTY, property);
            return this;
        }

        public Builder normalize(final boolean normalize) {
            this.configuration.setProperty(NORMALIZE, normalize);
            return this;
        }

        public Builder bucketMillis(final long bucketMillis) {
            this.configuration.setProperty(BUCKET_MILLIS, bucketMillis);
            return this;
        }

        public Builder activeWindow(final Lifetime window) {
            if (window == null) {
                this.configuration.clearProperty(ACTIVE_WINDOW);
            } else {
                this.configuration.setProperty(ACTIVE_WINDOW, window.toTemporalIntervalsString());
            }
            return this;
        }

        public Builder scopedByHaltedTraversers(final boolean scoped) {
            this.configuration.setProperty(SCOPED_BY_HALTED_TRAVERSERS, scoped);
            return this;
        }

        public Builder haltedTraversers(final TraverserSet<Vertex> haltedTraversers) {
            if (haltedTraversers != null && !haltedTraversers.isEmpty()) {
                TraversalVertexProgram.storeHaltedTraversers(this.configuration, haltedTraversers);
            }
            return this;
        }
    }
}
