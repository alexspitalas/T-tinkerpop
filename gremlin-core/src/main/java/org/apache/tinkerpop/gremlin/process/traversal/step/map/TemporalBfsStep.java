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
        final Queue<Vertex> queue = new LinkedList<>();
        
        gamma.put(source.id(), this.initialTime);
        queue.add(source);
        
        final List<Vertex> reachable = new ArrayList<>();
        
        if (target == TemporalPathTarget.EARLIEST_ARRIVAL || target == TemporalPathTarget.EARLIEST_DEPARTURE) {
            // Forward BFS
            while (!queue.isEmpty()) {
                Vertex u = queue.poll();
                long currentTime = gamma.get(u.id());
                
                Iterator<Edge> edges = u.edges(this.direction, this.edgeLabels);
                while (edges.hasNext()) {
                    Edge e = edges.next();
                    Lifetime lifetime = Lifetime.fromProperties(e);
                    if (lifetime.isEmpty()) continue;
                    
                    // Multi-interval sweep: find the first valid interval after currentTime
                    long bestStart = -1;
                    long bestEnd = -1;
                    
                    for (Lifetime.Interval interval : lifetime.getIntervals()) {
                        long intStart = interval.getStart().getTime();
                        long intEnd = interval.getEnd().getTime();
                        
                        if (intStart >= currentTime) {
                            bestStart = intStart;
                            bestEnd = intEnd;
                            break; // Found the earliest valid interval
                        }
                    }
                    
                    if (bestStart != -1) {
                        Vertex v = e.inVertex(); // Assuming OUT direction for forward BFS, to be precise we should use e.vertices(direction.opposite())
                        // Fix for direction
                        Vertex nextVertex = direction == Direction.OUT ? e.inVertex() : (direction == Direction.IN ? e.outVertex() : (u.equals(e.outVertex()) ? e.inVertex() : e.outVertex()));

                        if (target == TemporalPathTarget.EARLIEST_ARRIVAL) {
                            if (!gamma.containsKey(nextVertex.id()) || bestEnd < gamma.get(nextVertex.id())) {
                                gamma.put(nextVertex.id(), bestEnd);
                                queue.add(nextVertex);
                                if (!reachable.contains(nextVertex) && !nextVertex.equals(source)) reachable.add(nextVertex);
                            }
                        } else if (target == TemporalPathTarget.EARLIEST_DEPARTURE) {
                            long existing = gamma.containsKey(nextVertex.id()) ? gamma.get(nextVertex.id()) : Long.MAX_VALUE;
                            if (bestStart < existing) {
                                gamma.put(nextVertex.id(), bestStart);
                                queue.add(nextVertex);
                                if (!reachable.contains(nextVertex) && !nextVertex.equals(source)) reachable.add(nextVertex);
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
                long currentTime = gamma.get(u.id());
                
                Iterator<Edge> edges = u.edges(this.direction, this.edgeLabels);
                while (edges.hasNext()) {
                    Edge e = edges.next();
                    Lifetime lifetime = Lifetime.fromProperties(e);
                    if (lifetime.isEmpty()) continue;
                    
                    // Multi-interval sweep backwards: find the last valid interval before currentTime
                    long bestStart = -1;
                    long bestEnd = -1;
                    
                    List<Lifetime.Interval> intervals = lifetime.getIntervals();
                    for (int i = intervals.size() - 1; i >= 0; i--) {
                        Lifetime.Interval interval = intervals.get(i);
                        long intStart = interval.getStart().getTime();
                        long intEnd = interval.getEnd().getTime();
                        
                        if (intEnd <= currentTime) {
                            bestStart = intStart;
                            bestEnd = intEnd;
                            break; 
                        }
                    }
                    
                    if (bestEnd != -1) {
                        Vertex nextVertex = direction == Direction.OUT ? e.inVertex() : (direction == Direction.IN ? e.outVertex() : (u.equals(e.outVertex()) ? e.inVertex() : e.outVertex()));

                        if (target == TemporalPathTarget.LATEST_DEPARTURE) {
                            long existing = gamma.containsKey(nextVertex.id()) ? gamma.get(nextVertex.id()) : -1;
                            if (bestStart > existing) {
                                gamma.put(nextVertex.id(), bestStart);
                                queue.add(nextVertex);
                                if (!reachable.contains(nextVertex) && !nextVertex.equals(source)) reachable.add(nextVertex);
                            }
                        } else if (target == TemporalPathTarget.LATEST_ARRIVAL) {
                            long existing = gamma.containsKey(nextVertex.id()) ? gamma.get(nextVertex.id()) : -1;
                            if (bestEnd > existing) {
                                gamma.put(nextVertex.id(), bestEnd);
                                queue.add(nextVertex);
                                if (!reachable.contains(nextVertex) && !nextVertex.equals(source)) reachable.add(nextVertex);
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

    @Override
    public <S, E> List<Traversal.Admin<S, E>> getLocalChildren() {
        return Collections.emptyList();
    }

    @Override
    public <S, E> List<Traversal.Admin<S, E>> getGlobalChildren() {
        return Collections.emptyList();
    }
}
