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
import org.apache.tinkerpop.gremlin.process.computer.util.AbstractVertexProgramBuilder;
import org.apache.tinkerpop.gremlin.process.traversal.Operator;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AllenFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TemporalPathFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.PureTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalUtil;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A context-aware PageRank variant that propagates rank across temporal edge sequences.
 */
public class TemporalPageRankVertexProgram implements VertexProgram<TemporalPageRankVertexProgram.TemporalRankMessage> {

    public static final String PAGE_RANK = "gremlin.temporalPageRankVertexProgram.pageRank";

    private static final String PROPERTY = "gremlin.temporalPageRankVertexProgram.property";
    private static final String VERTEX_COUNT = "gremlin.temporalPageRankVertexProgram.vertexCount";
    private static final String ALPHA = "gremlin.temporalPageRankVertexProgram.alpha";
    private static final String EPSILON = "gremlin.temporalPageRankVertexProgram.epsilon";
    private static final String MAX_ITERATIONS = "gremlin.temporalPageRankVertexProgram.maxIterations";
    private static final String EDGE_TRAVERSAL = "gremlin.temporalPageRankVertexProgram.edgeTraversal";
    private static final String INITIAL_RANK_TRAVERSAL = "gremlin.temporalPageRankVertexProgram.initialRankTraversal";
    private static final String TELEPORTATION_ENERGY = "gremlin.temporalPageRankVertexProgram.teleportationEnergy";
    private static final String CONVERGENCE_ERROR = "gremlin.temporalPageRankVertexProgram.convergenceError";
    private static final String FILTER = "gremlin.temporalPageRankVertexProgram.filter";
    private static final String MIN_DELAY = "gremlin.temporalPageRankVertexProgram.minDelay";
    private static final String MAX_DELAY = "gremlin.temporalPageRankVertexProgram.maxDelay";

    private static final DateTimeFormatter[] DATE_TIME_FORMATTERS = new DateTimeFormatter[] {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    };

    private PureTraversal<Vertex, Edge> edgeTraversal = new PureTraversal<>(__.<Vertex>outE().asAdmin());
    private PureTraversal<Vertex, ? extends Number> initialRankTraversal = null;
    private double alpha = 0.85d;
    private double epsilon = 0.00001d;
    private int maxIterations = 20;
    private TemporalPathFilterStep.TemporalPathType filter = TemporalPathFilterStep.TemporalPathType.SEQUENTIAL;
    private long minDelay = 0L;
    private long maxDelay = Long.MAX_VALUE;
    private String property = PAGE_RANK;
    private Set<VertexComputeKey> vertexComputeKeys;
    private Set<MemoryComputeKey> memoryComputeKeys;

    private TemporalPageRankVertexProgram() {
    }

    @Override
    public void loadState(final Graph graph, final Configuration configuration) {
        if (configuration.containsKey(INITIAL_RANK_TRAVERSAL))
            this.initialRankTraversal = PureTraversal.loadState(configuration, INITIAL_RANK_TRAVERSAL, graph);
        if (configuration.containsKey(EDGE_TRAVERSAL))
            this.edgeTraversal = PureTraversal.loadState(configuration, EDGE_TRAVERSAL, graph);
        this.alpha = configuration.getDouble(ALPHA, this.alpha);
        this.epsilon = configuration.getDouble(EPSILON, this.epsilon);
        this.maxIterations = configuration.getInt(MAX_ITERATIONS, this.maxIterations);
        this.filter = parseFilter(configuration.getString(FILTER, this.filter.name()));
        this.minDelay = configuration.getLong(MIN_DELAY, this.minDelay);
        this.maxDelay = configuration.getLong(MAX_DELAY, this.maxDelay);
        this.property = configuration.getString(PROPERTY, PAGE_RANK);
        this.vertexComputeKeys = new HashSet<>(Collections.singletonList(VertexComputeKey.of(this.property, false)));
        this.memoryComputeKeys = new HashSet<>(Arrays.asList(
                MemoryComputeKey.of(TELEPORTATION_ENERGY, Operator.sum, true, true),
                MemoryComputeKey.of(VERTEX_COUNT, Operator.sum, true, true),
                MemoryComputeKey.of(CONVERGENCE_ERROR, Operator.sum, false, true)));
    }

    @Override
    public void storeState(final Configuration configuration) {
        VertexProgram.super.storeState(configuration);
        configuration.setProperty(ALPHA, this.alpha);
        configuration.setProperty(EPSILON, this.epsilon);
        configuration.setProperty(PROPERTY, this.property);
        configuration.setProperty(MAX_ITERATIONS, this.maxIterations);
        configuration.setProperty(FILTER, this.filter.name());
        configuration.setProperty(MIN_DELAY, this.minDelay);
        configuration.setProperty(MAX_DELAY, this.maxDelay);
        this.edgeTraversal.storeState(configuration, EDGE_TRAVERSAL);
        if (null != this.initialRankTraversal)
            this.initialRankTraversal.storeState(configuration, INITIAL_RANK_TRAVERSAL);
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
            final TemporalPageRankVertexProgram clone = (TemporalPageRankVertexProgram) super.clone();
            clone.edgeTraversal = this.edgeTraversal.clone();
            if (null != this.initialRankTraversal)
                clone.initialRankTraversal = this.initialRankTraversal.clone();
            return clone;
        } catch (final CloneNotSupportedException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void setup(final Memory memory) {
        memory.set(TELEPORTATION_ENERGY, null == this.initialRankTraversal ? 1.0d : 0.0d);
        memory.set(VERTEX_COUNT, 0.0d);
        memory.set(CONVERGENCE_ERROR, 1.0d);
    }

    @Override
    public void execute(final Vertex vertex, final Messenger<TemporalRankMessage> messenger, final Memory memory) {
        if (memory.isInitialIteration()) {
            memory.add(VERTEX_COUNT, 1.0d);
            return;
        }

        final double vertexCount = memory.<Double>get(VERTEX_COUNT);
        final List<TemporalRankMessage> incomingMessages = new ArrayList<>();
        messenger.receiveMessages().forEachRemaining(incomingMessages::add);

        double contextualRank = 0.0d;
        for (final TemporalRankMessage message : incomingMessages) {
            contextualRank += message.getRank();
        }

        double contextFreeRank = 0.0d;
        if (1 == memory.getIteration() && null != this.initialRankTraversal)
            contextFreeRank += TraversalUtil.apply(vertex, this.initialRankTraversal.get()).doubleValue();

        final double teleportationEnergy = memory.get(TELEPORTATION_ENERGY);
        if (teleportationEnergy > 0.0d) {
            final double localTeleportationEnergy = teleportationEnergy / vertexCount;
            contextFreeRank += localTeleportationEnergy;
            memory.add(TELEPORTATION_ENERGY, -localTeleportationEnergy);
        }

        final double pageRank = contextualRank + contextFreeRank;
        final double previousPageRank = vertex.<Double>property(this.property).orElse(0.0d);
        memory.add(CONVERGENCE_ERROR, Math.abs(pageRank - previousPageRank));
        vertex.property(VertexProperty.Cardinality.single, this.property, pageRank);

        memory.add(TELEPORTATION_ENERGY, (1.0d - this.alpha) * pageRank);

        if (contextFreeRank > 0.0d)
            this.distributeRank(vertex, contextFreeRank * this.alpha, null, null, messenger, memory);

        for (final TemporalRankMessage message : incomingMessages) {
            this.distributeRank(vertex, message.getRank() * this.alpha, message.getContextStartTime(),
                    message.getContextEndTime(), messenger, memory);
        }
    }

    @Override
    public boolean terminate(final Memory memory) {
        final boolean terminate = memory.<Double>get(CONVERGENCE_ERROR) < this.epsilon || memory.getIteration() >= this.maxIterations;
        memory.set(CONVERGENCE_ERROR, 0.0d);
        return terminate;
    }

    @Override
    public String toString() {
        return StringFactory.vertexProgramString(this,
                "alpha=" + this.alpha + ", epsilon=" + this.epsilon + ", iterations=" + this.maxIterations + ", filter=" + this.filter);
    }

    private void distributeRank(final Vertex vertex, final double rank, final String contextStartTime,
                                final String contextEndTime, final Messenger<TemporalRankMessage> messenger,
                                final Memory memory) {
        if (rank <= 0.0d)
            return;

        final List<OutgoingMessage> allowedMessages = new ArrayList<>();
        final Traversal.Admin<Vertex, Edge> candidateEdges = this.edgeTraversal.getPure();
        candidateEdges.addStart(candidateEdges.getTraverserGenerator().generate(vertex, candidateEdges.getStartStep(), 1L));
        try {
            while (candidateEdges.hasNext()) {
                final Edge edge = candidateEdges.next();
                final OutgoingMessage outgoingMessage = this.createOutgoingMessage(contextStartTime, contextEndTime, edge, vertex);
                if (null != outgoingMessage)
                    allowedMessages.add(outgoingMessage);
            }
        } finally {
            CloseableIterator.closeIterator(candidateEdges);
        }

        if (allowedMessages.isEmpty()) {
            memory.add(TELEPORTATION_ENERGY, rank);
            return;
        }

        final double share = rank / allowedMessages.size();
        for (final OutgoingMessage outgoingMessage : allowedMessages) {
            messenger.sendMessage(MessageScope.Global.of(outgoingMessage.vertex),
                    new TemporalRankMessage(share, outgoingMessage.contextStartTime, outgoingMessage.contextEndTime));
        }
    }

    private OutgoingMessage createOutgoingMessage(final String contextStartTime, final String contextEndTime,
                                                  final Edge edge, final Vertex sourceVertex) {
        final String edgeStartTime = this.getStartTime(edge);
        if (null == edgeStartTime)
            return null;

        final String edgeEndTime = this.getEndTime(edge);
        final Vertex otherVertex = this.getOtherVertex(edge, sourceVertex);
        if (null == otherVertex)
            return null;

        if (null == contextStartTime)
            return new OutgoingMessage(otherVertex, edgeStartTime, edgeEndTime);

        final LocalDateTime previousStart = this.parseDateTime(contextStartTime);
        final LocalDateTime previousEnd = null == contextEndTime ? LocalDateTime.MAX : this.parseDateTime(contextEndTime);
        final LocalDateTime edgeStart = this.parseDateTime(edgeStartTime);
        final LocalDateTime edgeEnd = null == edgeEndTime ? LocalDateTime.MAX : this.parseDateTime(edgeEndTime);

        if (null == previousStart || null == previousEnd || null == edgeStart || null == edgeEnd)
            return null;

        switch (this.filter) {
            case CONTINUOUS:
                return this.createContinuousMessage(otherVertex, previousStart, previousEnd, edgeStart, edgeEnd);
            case PAIRWISE_CONTINUOUS:
                return this.createPairwiseContinuousMessage(otherVertex, previousStart, previousEnd, edgeStart, edgeEnd);
            case SEQUENTIAL:
                return this.createSequentialMessage(otherVertex, previousStart, previousEnd, edgeStart, edgeEnd, edgeStartTime, edgeEndTime);
            default:
                return null;
        }
    }

    private String getStartTime(final Edge edge) {
        return this.getProperty(edge, "startTime");
    }

    private String getEndTime(final Edge edge) {
        return this.getProperty(edge, "endTime");
    }

    private String getProperty(final Edge edge, final String key) {
        try {
            return edge.property(key).isPresent() ? edge.property(key).value().toString() : null;
        } catch (final Exception ignored) {
            return null;
        }
    }

    private LocalDateTime parseDateTime(final String dateTime) {
        for (final DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(dateTime, formatter);
            } catch (final DateTimeParseException ignored) {
            }
        }

        try {
            return LocalDateTime.parse(dateTime);
        } catch (final DateTimeParseException ignored) {
            return null;
        }
    }

    public static Builder build() {
        return new Builder();
    }

    public static final class Builder extends AbstractVertexProgramBuilder<Builder> {

        private Builder() {
            super(TemporalPageRankVertexProgram.class);
        }

        public Builder iterations(final int iterations) {
            this.configuration.setProperty(MAX_ITERATIONS, iterations);
            return this;
        }

        public Builder alpha(final double alpha) {
            this.configuration.setProperty(ALPHA, alpha);
            return this;
        }

        public Builder property(final String key) {
            this.configuration.setProperty(PROPERTY, key);
            return this;
        }

        public Builder epsilon(final double epsilon) {
            this.configuration.setProperty(EPSILON, epsilon);
            return this;
        }

        public Builder filter(final TemporalPathFilterStep.TemporalPathType filter) {
            this.configuration.setProperty(FILTER, filter.name());
            return this;
        }

        public Builder edges(final Traversal.Admin<Vertex, Edge> edgeTraversal) {
            PureTraversal.storeState(this.configuration, EDGE_TRAVERSAL, edgeTraversal);
            return this;
        }

        public Builder initialRank(final Traversal.Admin<Vertex, ? extends Number> initialRankTraversal) {
            PureTraversal.storeState(this.configuration, INITIAL_RANK_TRAVERSAL, initialRankTraversal);
            return this;
        }

        public Builder minDelay(final long minDelay) {
            this.configuration.setProperty(MIN_DELAY, minDelay);
            return this;
        }

        public Builder maxDelay(final long maxDelay) {
            this.configuration.setProperty(MAX_DELAY, maxDelay);
            return this;
        }
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

    public static final class TemporalRankMessage implements Serializable {
        private final double rank;
        private final String contextStartTime;
        private final String contextEndTime;

        public TemporalRankMessage(final double rank, final String contextStartTime, final String contextEndTime) {
            this.rank = rank;
            this.contextStartTime = contextStartTime;
            this.contextEndTime = contextEndTime;
        }

        public double getRank() {
            return this.rank;
        }

        public String getContextStartTime() {
            return this.contextStartTime;
        }

        public String getContextEndTime() {
            return this.contextEndTime;
        }
    }

    private static TemporalPathFilterStep.TemporalPathType parseFilter(final String filter) {
        try {
            return TemporalPathFilterStep.TemporalPathType.valueOf(filter.trim().toUpperCase(Locale.ENGLISH));
        } catch (final IllegalArgumentException ignored) {
            throw new IllegalArgumentException("Unsupported temporal PageRank filter: " + filter);
        }
    }

    private OutgoingMessage createSequentialMessage(final Vertex otherVertex, final LocalDateTime previousStart,
                                                    final LocalDateTime previousEnd, final LocalDateTime edgeStart,
                                                    final LocalDateTime edgeEnd, final String edgeStartTime,
                                                    final String edgeEndTime) {
        final boolean isSequential = AllenFilterStep.evaluate(AllenFilterStep.AllenRelation.BEFORE, previousStart,
                previousEnd, edgeStart, edgeEnd) || AllenFilterStep.evaluate(AllenFilterStep.AllenRelation.MEETS,
                previousStart, previousEnd, edgeStart, edgeEnd);
        if (!isSequential)
            return null;

        if (this.minDelay > 0 || this.maxDelay < Long.MAX_VALUE) {
            try {
                final long delay = ChronoUnit.MILLIS.between(previousEnd, edgeStart);
                if (delay < this.minDelay || delay > this.maxDelay)
                    return null;
            } catch (final Exception ignored) {
                return new OutgoingMessage(otherVertex, edgeStartTime, edgeEndTime);
            }
        }

        return new OutgoingMessage(otherVertex, edgeStartTime, edgeEndTime);
    }

    private OutgoingMessage createPairwiseContinuousMessage(final Vertex otherVertex, final LocalDateTime previousStart,
                                                            final LocalDateTime previousEnd, final LocalDateTime edgeStart,
                                                            final LocalDateTime edgeEnd) {
        final boolean isBefore = AllenFilterStep.evaluate(AllenFilterStep.AllenRelation.BEFORE, previousStart,
                previousEnd, edgeStart, edgeEnd);
        final boolean isAfter = AllenFilterStep.evaluate(AllenFilterStep.AllenRelation.AFTER, previousStart,
                previousEnd, edgeStart, edgeEnd);
        if (isBefore || isAfter)
            return null;

        return new OutgoingMessage(otherVertex, formatDateTime(edgeStart), formatDateTime(edgeEnd));
    }

    private OutgoingMessage createContinuousMessage(final Vertex otherVertex, final LocalDateTime previousStart,
                                                    final LocalDateTime previousEnd, final LocalDateTime edgeStart,
                                                    final LocalDateTime edgeEnd) {
        final LocalDateTime intersectionStart = previousStart.isAfter(edgeStart) ? previousStart : edgeStart;
        final LocalDateTime intersectionEnd = previousEnd.isBefore(edgeEnd) ? previousEnd : edgeEnd;
        if (intersectionStart.isAfter(intersectionEnd))
            return null;

        return new OutgoingMessage(otherVertex, formatDateTime(intersectionStart), formatDateTime(intersectionEnd));
    }

    private Vertex getOtherVertex(final Edge edge, final Vertex sourceVertex) {
        Vertex otherVertex = edge.inVertex();
        if (otherVertex.equals(sourceVertex))
            otherVertex = edge.outVertex();
        return otherVertex;
    }

    private static String formatDateTime(final LocalDateTime dateTime) {
        return LocalDateTime.MAX.equals(dateTime) ? null : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dateTime);
    }

    private static final class OutgoingMessage {
        private final Vertex vertex;
        private final String contextStartTime;
        private final String contextEndTime;

        private OutgoingMessage(final Vertex vertex, final String contextStartTime, final String contextEndTime) {
            this.vertex = vertex;
            this.contextStartTime = contextStartTime;
            this.contextEndTime = contextEndTime;
        }
    }
}
