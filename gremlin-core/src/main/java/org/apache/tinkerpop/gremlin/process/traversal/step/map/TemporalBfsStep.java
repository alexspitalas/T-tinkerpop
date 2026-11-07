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

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.Configuring;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.Parameters;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalElement;
import org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget;

import java.util.*;

public class TemporalBfsStep extends ScalarMapStep<Vertex, Map<Vertex, Long>> implements Configuring, org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent {

    private Parameters parameters = new Parameters();
    private TemporalPathTarget target;
    private Direction direction;
    private String[] edgeLabels;
    private long initialTime;

    public TemporalBfsStep(final Traversal.Admin traversal, final long initialTime, final TemporalPathTarget target, final Direction direction, final String... edgeLabels) {
        super(traversal);
        this.initialTime = initialTime;
        this.target = target;
        this.direction = direction;
        this.edgeLabels = edgeLabels;
    }

    @Override
    public Parameters getParameters() {
        return this.parameters;
    }

    @Override
    public void configure(final Object... keyValues) {
        this.parameters.set(this, keyValues);
    }

    @Override
    protected Map<Vertex, Long> map(final Traverser.Admin<Vertex> traverser) {
        final Vertex source = traverser.get();
        final Map<Object, Long> gamma = new HashMap<>();
        final Map<Object, Long> causalTime = new HashMap<>();
        final Queue<Vertex> queue = new ArrayDeque<>();
        final Set<Object> inQueue = new HashSet<>();
        
        gamma.put(source.id(), this.initialTime);
        causalTime.put(source.id(), this.initialTime);
        queue.add(source);
        inQueue.add(source.id());
        
        final Set<Vertex> reachable = new LinkedHashSet<>();
        
        if (target == TemporalPathTarget.EARLIEST_ARRIVAL || target == TemporalPathTarget.EARLIEST_DEPARTURE) {
            // Forward BFS
            while (!queue.isEmpty()) {
                Vertex u = queue.poll();
                inQueue.remove(u.id());
                long currentTime = causalTime.get(u.id());
                
                Iterator<Edge> edges = u.edges(this.direction, this.edgeLabels);
                while (edges.hasNext()) {
                    Edge e = edges.next();
                    Lifetime lifetime = resolveEdgeLifetime(e);
                    if (lifetime.isEmpty()) continue;
                    
                    // Multi-interval sweep: find the first valid interval after currentTime
                    final int optimalIdx = lifetime.getFirstIntervalIndexStartingAfter(currentTime);
                    if (optimalIdx >= 0) {
                        long bestStart = lifetime.getStartMillis(optimalIdx);
                        long bestEnd = lifetime.getEndMillis(optimalIdx);
                        Vertex nextVertex = direction == Direction.OUT ? e.inVertex() : (direction == Direction.IN ? e.outVertex() : (u.equals(e.outVertex()) ? e.inVertex() : e.outVertex()));
                        Object nextId = nextVertex.id();

                        if (target == TemporalPathTarget.EARLIEST_ARRIVAL) {
                            if (!gamma.containsKey(nextId) || bestEnd < gamma.get(nextId)) {
                                gamma.put(nextId, bestEnd);
                                causalTime.put(nextId, bestEnd);
                                if (inQueue.add(nextId)) {
                                    queue.add(nextVertex);
                                }
                                if (!nextVertex.equals(source)) reachable.add(nextVertex);
                            }
                        } else if (target == TemporalPathTarget.EARLIEST_DEPARTURE) {
                            if (!gamma.containsKey(nextId) || bestStart < gamma.get(nextId)) {
                                gamma.put(nextId, bestStart);
                                if (!nextVertex.equals(source)) reachable.add(nextVertex);
                            }
                            if (!causalTime.containsKey(nextId) || bestEnd < causalTime.get(nextId)) {
                                causalTime.put(nextId, bestEnd);
                                if (inQueue.add(nextId)) {
                                    queue.add(nextVertex);
                                }
                                if (!nextVertex.equals(source)) reachable.add(nextVertex);
                            }
                        }
                    }
                }
            }
        } else {
            // Backward BFS (Latest Departure / Latest Arrival)
            // Initial time is treated as a deadline.
            while (!queue.isEmpty()) {
                Vertex u = queue.poll();
                inQueue.remove(u.id());
                long currentTime = causalTime.get(u.id());
                
                Iterator<Edge> edges = u.edges(this.direction, this.edgeLabels);
                while (edges.hasNext()) {
                    Edge e = edges.next();
                    Lifetime lifetime = resolveEdgeLifetime(e);
                    if (lifetime.isEmpty()) continue;
                    
                    // Multi-interval sweep backwards: find the last valid interval ending at or before currentTime
                    final int optimalIdx = lifetime.getLastIntervalIndexEndingBefore(currentTime);
                    if (optimalIdx >= 0) {
                        long bestStart = lifetime.getStartMillis(optimalIdx);
                        long bestEnd = lifetime.getEndMillis(optimalIdx);
                        Vertex nextVertex = direction == Direction.OUT ? e.inVertex() : (direction == Direction.IN ? e.outVertex() : (u.equals(e.outVertex()) ? e.inVertex() : e.outVertex()));
                        Object nextId = nextVertex.id();

                        if (target == TemporalPathTarget.LATEST_DEPARTURE) {
                            if (!gamma.containsKey(nextId) || bestStart > gamma.get(nextId)) {
                                gamma.put(nextId, bestStart);
                                causalTime.put(nextId, bestStart);
                                if (inQueue.add(nextId)) {
                                    queue.add(nextVertex);
                                }
                                if (!nextVertex.equals(source)) reachable.add(nextVertex);
                            }
                        } else if (target == TemporalPathTarget.LATEST_ARRIVAL) {
                            if (!gamma.containsKey(nextId) || bestEnd > gamma.get(nextId)) {
                                gamma.put(nextId, bestEnd);
                                if (!nextVertex.equals(source)) reachable.add(nextVertex);
                            }
                            if (!causalTime.containsKey(nextId) || bestStart > causalTime.get(nextId)) {
                                causalTime.put(nextId, bestStart);
                                if (inQueue.add(nextId)) {
                                    queue.add(nextVertex);
                                }
                                if (!nextVertex.equals(source)) reachable.add(nextVertex);
                            }
                        }
                    }
                }
            }
        }
        
        Map<Vertex, Long> resultMap = new HashMap<>();
        for (Vertex v : reachable) {
            resultMap.put(v, gamma.get(v.id()));
        }
        return resultMap;
    }

    private static Lifetime resolveEdgeLifetime(final Edge edge) {
        return org.apache.tinkerpop.gremlin.util.LifetimeHelper.resolveEffectiveLifetime(edge);
    }

    @Override
    public <S, E> List<Traversal.Admin<S, E>> getLocalChildren() {
        return Collections.emptyList();
    }

    @Override
    public <S, E> List<Traversal.Admin<S, E>> getGlobalChildren() {
        return Collections.emptyList();
    }

    @Override
    public TemporalBfsStep clone() {
        final TemporalBfsStep clone = (TemporalBfsStep) super.clone();
        clone.parameters = this.parameters.clone();
        return clone;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(super.hashCode(), this.initialTime, this.target, this.direction, this.parameters);
        if (this.edgeLabels != null) {
            result = 31 * result + Arrays.hashCode(this.edgeLabels);
        }
        return result;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof TemporalBfsStep))
            return false;
        if (!super.equals(obj))
            return false;
        final TemporalBfsStep other = (TemporalBfsStep) obj;
        return this.initialTime == other.initialTime
                && this.target == other.target
                && this.direction == other.direction
                && Arrays.equals(this.edgeLabels, other.edgeLabels)
                && Objects.equals(this.parameters, other.parameters);
    }

    @Override
    public String toString() {
        return org.apache.tinkerpop.gremlin.structure.util.StringFactory.stepString(
                this, this.initialTime, this.target, this.direction,
                this.edgeLabels == null ? Collections.emptyList() : Arrays.asList(this.edgeLabels), this.parameters);
    }
}
