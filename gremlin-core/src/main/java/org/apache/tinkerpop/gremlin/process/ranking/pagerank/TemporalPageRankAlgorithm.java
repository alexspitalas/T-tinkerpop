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

import org.apache.tinkerpop.gremlin.structure.temporal.TemporalEdge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
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
 * at the source versus moved to the target. Final {@code r} values may be normalized by the caller.
 * <p>
 * This is not static PageRank power iteration and does not use a convergence loop.
 */
public final class TemporalPageRankAlgorithm {

    public static final double DEFAULT_ALPHA = 0.85d;
    public static final double DEFAULT_BETA = 0.5d;
    public static final boolean DEFAULT_NORMALIZE = true;

    private TemporalPageRankAlgorithm() {
    }

    public static Map<Object, Double> execute(final Collection<TemporalEdge> temporalEdges,
                                              final double alpha, final double beta, final boolean normalize) {
        validate(alpha, beta);
        final List<TemporalEdge> sortedTemporalEdges = new ArrayList<>(temporalEdges);
        sortedTemporalEdges.sort(Comparator.comparing(TemporalEdge::getStartInstant)
                .thenComparing(edge -> String.valueOf(edge.id())));

        final Map<Object, Double> r = new LinkedHashMap<>();
        final Map<Object, Double> s = new LinkedHashMap<>();
        final Map<Object, Queue<ScheduledMass>> pending = new HashMap<>();
        final double injected = 1.0d - alpha;

        for (final TemporalEdge temporalEdge : sortedTemporalEdges) {
            final Object u = temporalEdge.outVertex().id();
            final Object v = temporalEdge.inVertex().id();
            releaseAvailableMass(pending, s, u, temporalEdge.getStartInstant());
            r.putIfAbsent(u, 0.0d);
            r.putIfAbsent(v, 0.0d);
            s.putIfAbsent(u, 0.0d);
            s.putIfAbsent(v, 0.0d);

            r.put(u, r.get(u) + injected);
            s.put(u, s.get(u) + injected);

            final double activeSourceMass = s.get(u);
            r.put(v, r.get(v) + activeSourceMass * alpha);

            if (Double.compare(beta, 1.0d) == 0) {
                scheduleMass(pending, v, activeSourceMass * alpha, temporalEdge.getEndInstant());
                s.put(u, 0.0d);
            } else {
                scheduleMass(pending, v, activeSourceMass * (1.0d - beta) * alpha, temporalEdge.getEndInstant());
                s.put(u, activeSourceMass * beta);
            }
        }

        if (normalize) {
            final double total = r.values().stream().mapToDouble(Double::doubleValue).sum();
            if (total != 0.0d)
                r.replaceAll((vertexId, rank) -> rank / total);
        }

        return r;
    }

    private static void validate(final double alpha, final double beta) {
        if (!(alpha > 0.0d && alpha < 1.0d))
            throw new IllegalArgumentException("Temporal PageRank alpha must be greater than 0.0 and less than 1.0");
        if (!(beta > 0.0d && beta <= 1.0d))
            throw new IllegalArgumentException("Temporal PageRank beta must be greater than 0.0 and less than or equal to 1.0");
    }

    private static void releaseAvailableMass(final Map<Object, Queue<ScheduledMass>> pending, final Map<Object, Double> s,
                                             final Object vertexId, final Date startTime) {
        final Queue<ScheduledMass> scheduled = pending.get(vertexId);
        if (null == scheduled)
            return;

        while (!scheduled.isEmpty() && !scheduled.peek().availableAt.after(startTime)) {
            s.put(vertexId, s.getOrDefault(vertexId, 0.0d) + scheduled.remove().mass);
        }
    }

    private static void scheduleMass(final Map<Object, Queue<ScheduledMass>> pending, final Object vertexId,
                                     final double mass, final Date availableAt) {
        if (mass == 0.0d)
            return;

        pending.computeIfAbsent(vertexId,
                key -> new PriorityQueue<>(Comparator.comparing((ScheduledMass scheduledMass) -> scheduledMass.availableAt)))
                .add(new ScheduledMass(availableAt, mass));
    }

    private static final class ScheduledMass {
        private final Date availableAt;
        private final double mass;

        private ScheduledMass(final Date availableAt, final double mass) {
            this.availableAt = new Date(Objects.requireNonNull(availableAt, "availableAt cannot be null").getTime());
            this.mass = mass;
        }
    }

}
