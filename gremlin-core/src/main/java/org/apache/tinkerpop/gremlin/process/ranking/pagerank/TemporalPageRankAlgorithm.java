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
package org.apache.tinkerpop.gremlin.process.ranking.pagerank;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;

/**
 * Implements the Rozenshtein-Gionis Temporal PageRank chronological edge-stream algorithm.
 * <p>
 * This computation scans temporal edges once in chronological lifetime-start order. It maintains {@code r}, the
 * accumulated Temporal PageRank score, and {@code s}, active temporal-walk mass waiting at each vertex. Mass moved
 * across an edge lifetime becomes active at the target only at the edge {@code endTime}, so walks follow the
 * sequential lifetime rule where a previous edge must end before or meet the next edge's start. The {@code alpha}
 * parameter controls continuation versus new walk injection, and {@code beta} controls how active mass is retained
 * at the source versus moved to the target. Final {@code r} values are normalized by default.
 * <p>
 * This is not static PageRank power iteration and does not use a convergence loop.
 */
public final class TemporalPageRankAlgorithm {

    public static final String TEMPORAL_PAGE_RANK = "gremlin.temporalPageRank.pageRank";
    public static final String DEFAULT_START_TIME_PROPERTY = "startTime";
    public static final String DEFAULT_END_TIME_PROPERTY = "endTime";
    public static final double DEFAULT_ALPHA = 0.85d;
    public static final double DEFAULT_BETA = 0.5d;
    public static final boolean DEFAULT_NORMALIZE = true;

    private final Config config;

    public TemporalPageRankAlgorithm() {
        this(new Config());
    }

    public TemporalPageRankAlgorithm(final Config config) {
        this.config = config;
    }

    public Map<Object, Double> execute(final Collection<TemporalEdge> temporalEdges) {
        final List<TemporalEdge> sortedTemporalEdges = new ArrayList<>(temporalEdges);
        sortedTemporalEdges.sort(TemporalEdge.chronological());

        final Map<Object, Double> r = new LinkedHashMap<>();
        final Map<Object, Double> s = new LinkedHashMap<>();
        final Map<Object, Queue<ScheduledMass>> pending = new HashMap<>();
        final double alpha = config.getAlpha();
        final double beta = config.getBeta();
        final double injected = 1.0d - alpha;

        for (final TemporalEdge temporalEdge : sortedTemporalEdges) {
            final Object u = temporalEdge.getOutVertexId();
            final Object v = temporalEdge.getInVertexId();
            releaseAvailableMass(pending, s, u, temporalEdge.getStartTime());
            r.putIfAbsent(u, 0.0d);
            r.putIfAbsent(v, 0.0d);
            s.putIfAbsent(u, 0.0d);
            s.putIfAbsent(v, 0.0d);

            r.put(u, r.get(u) + injected);
            s.put(u, s.get(u) + injected);

            final double activeSourceMass = s.get(u);
            r.put(v, r.get(v) + activeSourceMass * alpha);

            if (Double.compare(beta, 1.0d) == 0) {
                scheduleMass(pending, v, activeSourceMass * alpha, temporalEdge.getEndTime());
                s.put(u, 0.0d);
            } else {
                scheduleMass(pending, v, activeSourceMass * (1.0d - beta) * alpha, temporalEdge.getEndTime());
                s.put(u, activeSourceMass * beta);
            }
        }

        if (config.isNormalize()) {
            final double total = r.values().stream().mapToDouble(Double::doubleValue).sum();
            if (total != 0.0d)
                r.replaceAll((vertexId, rank) -> rank / total);
        }

        return r;
    }

    private void releaseAvailableMass(final Map<Object, Queue<ScheduledMass>> pending, final Map<Object, Double> s,
                                      final Object vertexId, final Instant startTime) {
        final Queue<ScheduledMass> scheduled = pending.get(vertexId);
        if (null == scheduled)
            return;

        while (!scheduled.isEmpty() && !scheduled.peek().availableAt.isAfter(startTime)) {
            s.put(vertexId, s.getOrDefault(vertexId, 0.0d) + scheduled.remove().mass);
        }
    }

    private void scheduleMass(final Map<Object, Queue<ScheduledMass>> pending, final Object vertexId, final double mass,
                              final Instant availableAt) {
        if (mass == 0.0d)
            return;

        pending.computeIfAbsent(vertexId,
                key -> new PriorityQueue<>(Comparator.comparing((ScheduledMass scheduledMass) -> scheduledMass.availableAt)))
                .add(new ScheduledMass(availableAt, mass));
    }

    private static final class ScheduledMass {
        private final Instant availableAt;
        private final double mass;

        private ScheduledMass(final Instant availableAt, final double mass) {
            this.availableAt = availableAt;
            this.mass = mass;
        }
    }

    public static final class TemporalEdge {
        private static final Comparator<TemporalEdge> CHRONOLOGICAL =
                Comparator.comparing(TemporalEdge::getStartTime)
                        .thenComparing(edge -> String.valueOf(edge.getEdgeId()));

        private final Object outVertexId;
        private final Object inVertexId;
        private final Object edgeId;
        private final Instant startTime;
        private final Instant endTime;

        public TemporalEdge(final Object outVertexId, final Object inVertexId, final Object edgeId,
                            final Instant startTime, final Instant endTime) {
            this.outVertexId = Objects.requireNonNull(outVertexId, "outVertexId cannot be null");
            this.inVertexId = Objects.requireNonNull(inVertexId, "inVertexId cannot be null");
            this.edgeId = Objects.requireNonNull(edgeId, "edgeId cannot be null");
            this.startTime = Objects.requireNonNull(startTime, "startTime cannot be null");
            this.endTime = Objects.requireNonNull(endTime, "endTime cannot be null");
            if (!startTime.isBefore(endTime))
                throw new IllegalArgumentException("Temporal edge startTime must be before endTime");
        }

        public Object getOutVertexId() {
            return outVertexId;
        }

        public Object getInVertexId() {
            return inVertexId;
        }

        public Object getEdgeId() {
            return edgeId;
        }

        public Instant getStartTime() {
            return startTime;
        }

        public Instant getEndTime() {
            return endTime;
        }

        public static Comparator<TemporalEdge> chronological() {
            return CHRONOLOGICAL;
        }
    }

    public Config getConfig() {
        return config;
    }

    public static final class Config {
        private final double alpha;
        private final double beta;
        private final String startTimeProperty;
        private final String endTimeProperty;
        private final String propertyName;
        private final boolean normalize;

        public Config() {
            this(DEFAULT_ALPHA, DEFAULT_BETA, DEFAULT_START_TIME_PROPERTY, DEFAULT_END_TIME_PROPERTY,
                    TEMPORAL_PAGE_RANK, DEFAULT_NORMALIZE);
        }

        public Config(final double alpha, final double beta, final String startTimeProperty, final String endTimeProperty,
                      final String propertyName, final boolean normalize) {
            if (!(alpha > 0.0d && alpha < 1.0d))
                throw new IllegalArgumentException("Temporal PageRank alpha must be greater than 0.0 and less than 1.0");
            if (!(beta > 0.0d && beta <= 1.0d))
                throw new IllegalArgumentException("Temporal PageRank beta must be greater than 0.0 and less than or equal to 1.0");
            if (null == startTimeProperty || startTimeProperty.trim().isEmpty())
                throw new IllegalArgumentException("Temporal PageRank startTimeProperty must be a non-empty String");
            if (null == endTimeProperty || endTimeProperty.trim().isEmpty())
                throw new IllegalArgumentException("Temporal PageRank endTimeProperty must be a non-empty String");
            if (null == propertyName || propertyName.trim().isEmpty())
                throw new IllegalArgumentException("Temporal PageRank propertyName must be a non-empty String");

            this.alpha = alpha;
            this.beta = beta;
            this.startTimeProperty = startTimeProperty;
            this.endTimeProperty = endTimeProperty;
            this.propertyName = propertyName;
            this.normalize = normalize;
        }

        public double getAlpha() {
            return alpha;
        }

        public double getBeta() {
            return beta;
        }

        public String getStartTimeProperty() {
            return startTimeProperty;
        }

        public String getEndTimeProperty() {
            return endTimeProperty;
        }

        public String getPropertyName() {
            return propertyName;
        }

        public boolean isNormalize() {
            return normalize;
        }
    }
}
