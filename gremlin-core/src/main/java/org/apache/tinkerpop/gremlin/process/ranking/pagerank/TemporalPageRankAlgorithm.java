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

import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalEdge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        if (temporalEdges.isEmpty()) {
            return new LinkedHashMap<>();
        }

        final Map<Object, VertexState> vertexStates = new HashMap<>();
        final List<Transition> transitions = new ArrayList<>(temporalEdges.size());

        for (final TemporalEdge temporalEdge : temporalEdges) {
            final Lifetime lt = temporalEdge.getLifetime();
            final int count = lt.getIntervalCount();
            if (count == 0) continue;

            final Object uId = temporalEdge.getOutVertexId();
            final Object vId = temporalEdge.getInVertexId();
            final Object edgeId = temporalEdge.id();
            final VertexState uState = vertexStates.computeIfAbsent(uId, VertexState::new);
            final VertexState vState = vertexStates.computeIfAbsent(vId, VertexState::new);

            for (int i = 0; i < count; i++) {
                transitions.add(new Transition(uState, vState, edgeId, lt.getStartMillis(i), lt.getEndMillis(i)));
            }
        }

        final int m = transitions.size();
        boolean alreadySorted = true;
        for (int i = 1; i < m; i++) {
            if (compareTransitions(transitions.get(i - 1), transitions.get(i)) > 0) {
                alreadySorted = false;
                break;
            }
        }
        if (!alreadySorted) {
            transitions.sort(TemporalPageRankAlgorithm::compareTransitions);
        }

        final List<VertexState> encounterOrder = new ArrayList<>(vertexStates.size());
        final double injected = 1.0d - alpha;
        final boolean betaIsOne = Double.compare(beta, 1.0d) == 0;
        final double oneMinusBetaAlpha = (1.0d - beta) * alpha;

        for (int i = 0; i < m; i++) {
            final Transition t = transitions.get(i);
            final VertexState u = t.u;
            final VertexState v = t.v;

            u.releaseAvailableMass(t.startMs);
            if (!u.visited) {
                u.visited = true;
                encounterOrder.add(u);
            }
            if (!v.visited) {
                v.visited = true;
                encounterOrder.add(v);
            }

            u.r += injected;
            u.s += injected;

            final double activeSourceMass = u.s;
            v.r += activeSourceMass * alpha;

            if (betaIsOne) {
                v.scheduleMass(activeSourceMass * alpha, t.endMs);
                u.s = 0.0d;
            } else {
                v.scheduleMass(activeSourceMass * oneMinusBetaAlpha, t.endMs);
                u.s = activeSourceMass * beta;
            }
        }

        double normFactor = 1.0d;
        if (normalize) {
            double total = 0.0d;
            for (int i = 0, n = encounterOrder.size(); i < n; i++) {
                total += encounterOrder.get(i).r;
            }
            if (total != 0.0d) {
                normFactor = 1.0d / total;
            }
        }

        final Map<Object, Double> r = new LinkedHashMap<>(encounterOrder.size());
        for (int i = 0, n = encounterOrder.size(); i < n; i++) {
            final VertexState vs = encounterOrder.get(i);
            r.put(vs.vertexId, normalize ? vs.r * normFactor : vs.r);
        }

        return r;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareTransitions(final Transition t1, final Transition t2) {
        final int cmp = Long.compare(t1.startMs, t2.startMs);
        if (cmp != 0) return cmp;
        final Object id1 = t1.edgeId;
        final Object id2 = t2.edgeId;
        if (id1 == id2) return 0;
        if (id1 instanceof Comparable && id2 instanceof Comparable && id1.getClass().equals(id2.getClass())) {
            return ((Comparable) id1).compareTo(id2);
        }
        return String.valueOf(id1).compareTo(String.valueOf(id2));
    }

    private static void validate(final double alpha, final double beta) {
        if (!(alpha > 0.0d && alpha < 1.0d))
            throw new IllegalArgumentException("Temporal PageRank alpha must be greater than 0.0 and less than 1.0");
        if (!(beta > 0.0d && beta <= 1.0d))
            throw new IllegalArgumentException("Temporal PageRank beta must be greater than 0.0 and less than or equal to 1.0");
    }

    private static final class Transition {
        final VertexState u;
        final VertexState v;
        final Object edgeId;
        final long startMs;
        final long endMs;

        Transition(final VertexState u, final VertexState v, final Object edgeId, final long startMs, final long endMs) {
            this.u = u;
            this.v = v;
            this.edgeId = edgeId;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private static final class VertexState {
        final Object vertexId;
        double r = 0.0d;
        double s = 0.0d;
        boolean visited = false;

        long[] heapTimes = null;
        double[] heapMasses = null;
        int heapSize = 0;

        VertexState(final Object vertexId) {
            this.vertexId = vertexId;
        }

        void scheduleMass(final double mass, final long availableAtMillis) {
            if (mass == 0.0d) return;
            if (heapTimes == null) {
                heapTimes = new long[8];
                heapMasses = new double[8];
            } else if (heapSize == heapTimes.length) {
                final int newCap = heapTimes.length << 1;
                heapTimes = Arrays.copyOf(heapTimes, newCap);
                heapMasses = Arrays.copyOf(heapMasses, newCap);
            }
            int idx = heapSize++;
            while (idx > 0) {
                final int parent = (idx - 1) >>> 1;
                if (heapTimes[parent] <= availableAtMillis) {
                    break;
                }
                heapTimes[idx] = heapTimes[parent];
                heapMasses[idx] = heapMasses[parent];
                idx = parent;
            }
            heapTimes[idx] = availableAtMillis;
            heapMasses[idx] = mass;
        }

        void releaseAvailableMass(final long startTimeMs) {
            while (heapSize > 0 && heapTimes[0] <= startTimeMs) {
                this.s += heapMasses[0];
                final int last = --heapSize;
                if (last > 0) {
                    final long lastTime = heapTimes[last];
                    final double lastMass = heapMasses[last];
                    int idx = 0;
                    final int half = last >>> 1;
                    while (idx < half) {
                        int child = (idx << 1) + 1;
                        final int right = child + 1;
                        if (right < last && heapTimes[right] < heapTimes[child]) {
                            child = right;
                        }
                        if (lastTime <= heapTimes[child]) {
                            break;
                        }
                        heapTimes[idx] = heapTimes[child];
                        heapMasses[idx] = heapMasses[child];
                        idx = child;
                    }
                    heapTimes[idx] = lastTime;
                    heapMasses[idx] = lastMass;
                }
            }
        }
    }
}
