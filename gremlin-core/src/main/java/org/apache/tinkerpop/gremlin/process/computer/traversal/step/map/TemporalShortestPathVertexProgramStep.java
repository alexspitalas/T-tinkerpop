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

package org.apache.tinkerpop.gremlin.process.computer.traversal.step.map;

import org.apache.tinkerpop.gremlin.process.computer.ComputerResult;
import org.apache.tinkerpop.gremlin.process.computer.GraphFilter;
import org.apache.tinkerpop.gremlin.process.computer.Memory;
import org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget;
import org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalShortestPathVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.traversal.TraversalVertexProgram;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.Configuring;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.Parameters;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.process.traversal.util.PureTraversal;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.util.Serializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * @author Daniel Kuppitz (http://gremlin.guru)
 */
public final class TemporalShortestPathVertexProgramStep extends VertexProgramStep implements TraversalParent, Configuring {

    private Parameters parameters = new Parameters();
    private PureTraversal<Vertex, ?> targetVertexFilter = TemporalShortestPathVertexProgram.DEFAULT_VERTEX_FILTER_TRAVERSAL.clone();
    private PureTraversal<Vertex, Edge> edgeTraversal = TemporalShortestPathVertexProgram.DEFAULT_EDGE_TRAVERSAL.clone();
    private PureTraversal<Edge, Number> distanceTraversal = TemporalShortestPathVertexProgram.DEFAULT_DISTANCE_TRAVERSAL.clone();
    private Number maxDistance;
    private boolean includeEdges;
    private TemporalPathTarget target = TemporalPathTarget.EARLIEST_ARRIVAL;
    private long initialTime = 0L;

    public TemporalShortestPathVertexProgramStep(final Traversal.Admin<?, ?> traversal) {
        super(traversal);
    }

    public TemporalShortestPathVertexProgramStep(final Traversal.Admin<?, ?> traversal,
                                                 final TemporalPathTarget target,
                                                 final long initialTime) {
        super(traversal);
        if (target != null)
            this.target = target;
        this.initialTime = initialTime;
    }

    void setTargetVertexFilter(final Traversal filterTraversal) {
        this.targetVertexFilter = new PureTraversal<>(this.integrateChild(filterTraversal.asAdmin()));
    }

    public Traversal.Admin<Vertex, Edge> getEdgeTraversal() {
        return this.edgeTraversal.getPure();
    }

    public void setEdgeTraversal(final Traversal edgeTraversal) {
        this.edgeTraversal = new PureTraversal<>(this.integrateChild(edgeTraversal.asAdmin()));
    }

    void setDistanceTraversal(final Traversal distanceTraversal) {
        this.distanceTraversal = new PureTraversal<>(this.integrateChild(distanceTraversal.asAdmin()));
    }

    void setMaxDistance(final Number maxDistance) {
        this.maxDistance = maxDistance;
    }

    void setIncludeEdges(final boolean includeEdges) {
        this.includeEdges = includeEdges;
    }

    public void setTarget(final TemporalPathTarget target) {
        if (target != null)
            this.target = target;
    }

    public void setInitialTime(final long initialTime) {
        this.initialTime = initialTime;
    }

    @Override
    public void configure(final Object... keyValues) {
        if (null == keyValues) return;
        final List<Object> remaining = new ArrayList<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i + 1 < keyValues.length && keyValues[i] instanceof String) {
                final String key = (String) keyValues[i];
                final Object value = keyValues[i + 1];
                if (ShortestPath.target.equals(key) || "target".equals(key)
                        || TemporalShortestPathVertexProgram.TARGET_VERTEX_FILTER.equals(key)) {
                    if (value instanceof Traversal) {
                        setTargetVertexFilter((Traversal) value);
                        continue;
                    }
                    throw new IllegalArgumentException(key + " requires a Traversal as its argument");
                } else if (ShortestPath.edges.equals(key) || "edges".equals(key)
                        || TemporalShortestPathVertexProgram.EDGE_TRAVERSAL.equals(key)) {
                    if (value instanceof Traversal) {
                        setEdgeTraversal((Traversal) value);
                        continue;
                    } else if (value instanceof Direction) {
                        setEdgeTraversal(__.toE((Direction) value));
                        continue;
                    }
                    throw new IllegalArgumentException(key + " requires a Traversal or a Direction as its argument");
                } else if (ShortestPath.distance.equals(key) || "distance".equals(key)
                        || TemporalShortestPathVertexProgram.DISTANCE_TRAVERSAL.equals(key)) {
                    throw new IllegalArgumentException(
                            "temporalShortestPath() optimizes temporal timestamps and does not support custom distance traversals; use maxDistance to limit hop count");
                } else if (ShortestPath.maxDistance.equals(key) || "maxDistance".equals(key)
                        || TemporalShortestPathVertexProgram.MAX_DISTANCE.equals(key)) {
                    if (value instanceof Number) {
                        setMaxDistance((Number) value);
                        continue;
                    }
                    throw new IllegalArgumentException(key + " requires a Number as its argument");
                } else if (ShortestPath.includeEdges.equals(key) || "includeEdges".equals(key)
                        || TemporalShortestPathVertexProgram.INCLUDE_EDGES.equals(key)) {
                    if (value instanceof Boolean) {
                        setIncludeEdges((Boolean) value);
                        continue;
                    }
                    throw new IllegalArgumentException(key + " requires a Boolean as its argument");
                } else if (TemporalShortestPathVertexProgram.TARGET.equals(key)) {
                    if (value instanceof TemporalPathTarget) {
                        setTarget((TemporalPathTarget) value);
                        continue;
                    } else if (value instanceof String) {
                        setTarget(TemporalPathTarget.valueOf((String) value));
                        continue;
                    }
                    throw new IllegalArgumentException(key + " requires a TemporalPathTarget or String as its argument");
                } else if (TemporalShortestPathVertexProgram.INITIAL_TIME.equals(key)) {
                    setInitialTime(Lifetime.toTemporalMillis(value));
                    continue;
                }
            }
            remaining.add(keyValues[i]);
            if (i + 1 < keyValues.length) {
                remaining.add(keyValues[i + 1]);
            }
        }
        if (!remaining.isEmpty()) {
            this.parameters.set(this, remaining.toArray());
        }
    }

    @Override
    public Parameters getParameters() {
        return parameters;
    }

    @Override
    protected Traverser.Admin<ComputerResult> processNextStart() throws NoSuchElementException {
        return super.processNextStart();
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<Traversal.Admin<?, ?>> getLocalChildren() {
        return Arrays.asList(
                this.targetVertexFilter.get(),
                this.edgeTraversal.get(),
                this.distanceTraversal.get());
    }

    @Override
    public String toString() {
        return StringFactory.stepString(this, this.target, this.initialTime, this.targetVertexFilter.get(),
                this.edgeTraversal.get(), this.distanceTraversal.get(), this.maxDistance, this.includeEdges,
                new GraphFilter(this.computer));
    }

    @Override
    public TemporalShortestPathVertexProgram generateProgram(final Graph graph, final Memory memory) {

        final TemporalShortestPathVertexProgram.Builder builder = TemporalShortestPathVertexProgram.build()
                .target(this.target)
                .initialTime(this.initialTime)
                .target(this.targetVertexFilter.getPure())
                .edgeTraversal(this.edgeTraversal.getPure())
                .distanceTraversal(this.distanceTraversal.getPure())
                .maxDistance(this.maxDistance)
                .includeEdges(this.includeEdges);

        //noinspection unchecked
        final PureTraversal pureRootTraversal = new PureTraversal<>(this.traversal);
        Object rootTraversalValue;
        try {
            rootTraversalValue = Base64.getEncoder().encodeToString(Serializer.serializeObject(pureRootTraversal));
        } catch (final IOException ignored) {
            rootTraversalValue = pureRootTraversal;
        }

        builder.configure(
                ProgramVertexProgramStep.ROOT_TRAVERSAL, rootTraversalValue,
                ProgramVertexProgramStep.STEP_ID, this.id);

        // There are two locations in which halted traversers can be stored: in memory or as vertex properties. In the
        // former case they need to be copied to this VertexProgram's configuration as the VP won't have access to the
        // previous VP's memory.
        if (memory.exists(TraversalVertexProgram.HALTED_TRAVERSERS)) {
            final TraverserSet<?> haltedTraversers = memory.get(TraversalVertexProgram.HALTED_TRAVERSERS);
            if (!haltedTraversers.isEmpty()) {
                Object haltedTraversersValue;
                try {
                    haltedTraversersValue = Base64.getEncoder().encodeToString(Serializer.serializeObject(haltedTraversers));
                } catch (final IOException ignored) {
                    haltedTraversersValue = haltedTraversers;
                }
                builder.configure(TraversalVertexProgram.HALTED_TRAVERSERS, haltedTraversersValue);
            }
        }

        return builder.create(graph);
    }

    @Override
    public Set<TraverserRequirement> getRequirements() {
        return TraversalParent.super.getSelfAndChildRequirements();
    }

    @Override
    public TemporalShortestPathVertexProgramStep clone() {
        final TemporalShortestPathVertexProgramStep clone = (TemporalShortestPathVertexProgramStep) super.clone();
        clone.targetVertexFilter = this.targetVertexFilter.clone();
        clone.edgeTraversal = this.edgeTraversal.clone();
        clone.distanceTraversal = this.distanceTraversal.clone();
        clone.parameters = this.parameters.clone();
        return clone;
    }

    @Override
    public void setTraversal(final Traversal.Admin<?, ?> parentTraversal) {
        super.setTraversal(parentTraversal);
        this.integrateChild(this.targetVertexFilter.get());
        this.integrateChild(this.edgeTraversal.get());
        this.integrateChild(this.distanceTraversal.get());
    }

    @Override
    public int hashCode() {
        int result = super.hashCode() ^ this.target.hashCode() ^ Long.hashCode(this.initialTime)
                ^ this.targetVertexFilter.hashCode() ^ this.edgeTraversal.hashCode()
                ^ this.distanceTraversal.hashCode() ^ Boolean.hashCode(this.includeEdges)
                ^ this.parameters.hashCode();
        if (this.maxDistance != null)
            result ^= this.maxDistance.hashCode();
        return result;
    }

}