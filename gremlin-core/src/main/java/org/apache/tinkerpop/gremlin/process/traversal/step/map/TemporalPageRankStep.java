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
package org.apache.tinkerpop.gremlin.process.traversal.step.map;

import org.apache.tinkerpop.gremlin.process.ranking.pagerank.TemporalPageRankAlgorithm;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.Configuring;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.Parameters;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalEdge;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalVertex;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.util.LifetimeHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Traversal integration for Rozenshtein-Gionis Temporal PageRank.
 * <p>
 * The step uses incoming vertex traversers to select temporal edges whose out and in vertices are both within that
 * incoming vertex set. It parses each selected edge's {@code startTime}/{@code endTime} lifetime, delegates the
 * chronological lifetime-start scan to {@link TemporalPageRankAlgorithm}, and writes the resulting rank to the selected
 * vertices. Incoming vertex traversers are preserved for downstream traversal semantics.
 */
public final class TemporalPageRankStep<S> extends AbstractStep<S, S> implements Configuring {

    private static final String TEMPORAL_PAGE_RANK = "gremlin.temporalPageRank.pageRank";

    private Parameters parameters = new Parameters();
    private TraverserSet<S> barrier;
    private boolean computed = false;
    private double alpha = TemporalPageRankAlgorithm.DEFAULT_ALPHA;
    private double beta = TemporalPageRankAlgorithm.DEFAULT_BETA;
    private String propertyName = TEMPORAL_PAGE_RANK;
    private boolean normalize = TemporalPageRankAlgorithm.DEFAULT_NORMALIZE;
    private long bucketMillis = 0L;

    public TemporalPageRankStep(final Traversal.Admin traversal) {
        super(traversal);
        this.barrier = (TraverserSet<S>) this.traversal.getTraverserSetSupplier().get();
    }

    public double getAlpha() {
        return alpha;
    }

    public double getBeta() {
        return beta;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public boolean isNormalize() {
        return normalize;
    }

    public long getBucketMillis() {
        return bucketMillis;
    }

    @Override
    public void configure(final Object... keyValues) {
        if (null == keyValues || keyValues.length % 2 != 0) {
            this.parameters.set(null, keyValues);
            return;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            final Object key = keyValues[i];
            final Object value = keyValues[i + 1];
            if (TemporalPageRank.alpha.equals(key)) {
                if (!(value instanceof Number))
                    throw new IllegalArgumentException("TemporalPageRank.alpha requires a Number as its argument");
                this.alpha = ((Number) value).doubleValue();
            } else if (TemporalPageRank.beta.equals(key)) {
                if (!(value instanceof Number))
                    throw new IllegalArgumentException("TemporalPageRank.beta requires a Number as its argument");
                this.beta = ((Number) value).doubleValue();
            } else if (TemporalPageRank.propertyName.equals(key)) {
                if (!(value instanceof String))
                    throw new IllegalArgumentException("TemporalPageRank.propertyName requires a String as its argument");
                this.propertyName = (String) value;
            } else if (TemporalPageRank.normalize.equals(key)) {
                if (!(value instanceof Boolean))
                    throw new IllegalArgumentException("TemporalPageRank.normalize requires a Boolean as its argument");
                this.normalize = (Boolean) value;
            } else if (TemporalPageRank.bucketMillis.equals(key)) {
                this.bucketMillis = parseBucketMillis(value);
            } else {
                this.parameters.set(null, key, value);
            }
        }
    }

    private static long parseBucketMillis(final Object value) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("TemporalPageRank.bucketMillis requires a non-negative integral Number as its argument");
        }
        if (value instanceof Double || value instanceof Float) {
            final double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d) || d < 0.0d || d != Math.rint(d) || d > Long.MAX_VALUE) {
                throw new IllegalArgumentException("TemporalPageRank.bucketMillis requires a non-negative integral Number as its argument");
            }
        }
        final long millis = ((Number) value).longValue();
        if (millis < 0L) {
            throw new IllegalArgumentException("TemporalPageRank.bucketMillis requires a non-negative integral Number as its argument");
        }
        return millis;
    }

    @Override
    public Parameters getParameters() {
        return parameters;
    }

    @Override
    protected Traverser.Admin<S> processNextStart() {
        if (!computed)
            processAllStarts();

        if (barrier.isEmpty())
            throw FastNoSuchElementException.instance();
        return barrier.remove();
    }

    private void processAllStarts() {
        computed = true;
        final Map<Object, Vertex> scopedVertices = new LinkedHashMap<>();
        final Map<Object, Lifetime> vertexContexts = new LinkedHashMap<>();

        while (this.starts.hasNext()) {
            final Traverser.Admin<S> traverser = this.starts.next();
            if (!(traverser.get() instanceof Vertex))
                throw new IllegalArgumentException("temporalPageRank() can only be applied to vertex traversers");
            final Vertex vertex = (Vertex) traverser.get();
            scopedVertices.putIfAbsent(vertex.id(), baseVertex(vertex));
            if (vertex instanceof TemporalVertex) {
                vertexContexts.putIfAbsent(vertex.id(), ((TemporalVertex) vertex).getLifetime());
            }
            traverser.setStepId(this.getNextStep().getId());
            this.barrier.add(traverser);
        }

        validateConfiguration();
        if (this.barrier.isEmpty())
            return;

        final List<TemporalEdge> edges = readTemporalEdges(scopedVertices, vertexContexts);

        final Map<Object, Double> ranks = TemporalPageRankAlgorithm.execute(edges, alpha, beta, normalize, bucketMillis);
        writeRanks(scopedVertices.values(), propertyName, ranks);
    }

    private void validateConfiguration() {
        if (!(this.alpha > 0.0d && this.alpha < 1.0d))
            throw new IllegalArgumentException("Temporal PageRank alpha must be greater than 0.0 and less than 1.0");
        if (!(this.beta > 0.0d && this.beta <= 1.0d))
            throw new IllegalArgumentException("Temporal PageRank beta must be greater than 0.0 and less than or equal to 1.0");
        if (null == propertyName || propertyName.trim().isEmpty())
            throw new IllegalArgumentException("Temporal PageRank propertyName must be a non-empty String");
        if (this.bucketMillis < 0L)
            throw new IllegalArgumentException("Temporal PageRank bucketMillis must be non-negative");
    }

    private List<TemporalEdge> readTemporalEdges(final Map<Object, Vertex> scopedVertices,
                                                 final Map<Object, Lifetime> vertexContexts) {
        final List<TemporalEdge> temporalEdges = new ArrayList<>();
        final Set<Object> scopedVertexIds = new HashSet<>(scopedVertices.keySet());

        // Pre-check if any vertex has a restricted property lifetime to bypass redundant liveness checks
        final Map<Object, Lifetime> restrictedVertexLifetimes = new HashMap<>();
        boolean anyRestrictedVertex = false;
        for (final Vertex vertex : scopedVertices.values()) {
            if (Lifetime.hasLifetimeProperties(vertex)) {
                final Lifetime vLt = Lifetime.getLifetimeFromProperties(vertex);
                if (!vLt.isUnbounded()) {
                    anyRestrictedVertex = true;
                    restrictedVertexLifetimes.put(vertex.id(), vLt);
                }
            }
        }

        for (final Vertex vertex : scopedVertices.values()) {
            final Lifetime activeContext = vertexContexts.get(vertex.id());
            final Iterator<Edge> edges = vertex.edges(Direction.OUT);
            try {
                while (edges.hasNext()) {
                    final Edge rawEdge = edges.next();
                    final Object outId = rawEdge.outVertex().id();
                    final Object inId = rawEdge.inVertex().id();
                    if (!scopedVertexIds.contains(outId) || !scopedVertexIds.contains(inId)) {
                        continue;
                    }

                    Lifetime effectiveLifetime = Lifetime.getLifetimeFromProperties(rawEdge);
                    if (activeContext != null) {
                        effectiveLifetime = effectiveLifetime.intersection(activeContext);
                    }
                    final Lifetime inContext = vertexContexts.get(inId);
                    if (inContext != null) {
                        effectiveLifetime = effectiveLifetime.intersection(inContext);
                    }

                    if (effectiveLifetime.isEmpty()) {
                        continue;
                    }

                    if (anyRestrictedVertex) {
                        final Lifetime outLt = restrictedVertexLifetimes.get(outId);
                        if (outLt != null) {
                            effectiveLifetime = effectiveLifetime.intersection(outLt);
                            if (effectiveLifetime.isEmpty()) {
                                continue;
                            }
                        }
                        final Lifetime inLt = restrictedVertexLifetimes.get(inId);
                        if (inLt != null) {
                            effectiveLifetime = effectiveLifetime.intersection(inLt);
                            if (effectiveLifetime.isEmpty()) {
                                continue;
                            }
                        }
                    }
                    temporalEdges.add(new TemporalEdge(rawEdge, effectiveLifetime));
                }
            } finally {
                CloseableIterator.closeIterator(edges);
            }
        }

        return temporalEdges;
    }

    private Vertex baseVertex(final Vertex vertex) {
        return (Vertex) Lifetime.unwrapElement(vertex);
    }

    private void writeRanks(final Iterable<Vertex> vertices, final String propertyName,
                            final Map<Object, Double> ranks) {
        for (final Vertex vertex : vertices) {
            vertex.property(VertexProperty.Cardinality.single, propertyName,
                    ranks.getOrDefault(vertex.id(), 0.0d));
        }
    }

    @Override
    public Set<TraverserRequirement> getRequirements() {
        return Collections.singleton(TraverserRequirement.OBJECT);
    }

    @Override
    public TemporalPageRankStep<S> clone() {
        final TemporalPageRankStep<S> clone = (TemporalPageRankStep<S>) super.clone();
        clone.parameters = this.parameters.clone();
        clone.barrier = (TraverserSet<S>) this.traversal.getTraverserSetSupplier().get();
        clone.computed = false;
        return clone;
    }

    @Override
    public void reset() {
        super.reset();
        this.barrier.clear();
        this.computed = false;
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ Double.hashCode(this.alpha) ^ Double.hashCode(this.beta) ^
                this.propertyName.hashCode() ^ Boolean.hashCode(this.normalize) ^
                Long.hashCode(this.bucketMillis) ^ this.parameters.hashCode();
    }

    @Override
    public String toString() {
        return StringFactory.stepString(this, this.alpha, this.beta, this.propertyName, this.normalize, this.bucketMillis);
    }
}
