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

import org.apache.tinkerpop.gremlin.process.computer.GraphFilter;
import org.apache.tinkerpop.gremlin.process.computer.Memory;
import org.apache.tinkerpop.gremlin.process.computer.ranking.pagerank.TemporalPageRankVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.traversal.lambda.HaltedTraversersCountTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.Configuring;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TemporalPathFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.Parameters;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.util.PureTraversal;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TemporalPageRankVertexProgramStep extends VertexProgramStep implements TraversalParent, Configuring {

    private Parameters parameters = new Parameters();
    private PureTraversal<Vertex, Edge> edgeTraversal;
    private String pageRankProperty = TemporalPageRankVertexProgram.PAGE_RANK;
    private int times = 20;
    private TemporalPathFilterStep.TemporalPathType filter = TemporalPathFilterStep.TemporalPathType.SEQUENTIAL;
    private long minDelay = 0L;
    private long maxDelay = Long.MAX_VALUE;
    private final double alpha;

    public TemporalPageRankVertexProgramStep(final Traversal.Admin traversal, final double alpha) {
        super(traversal);
        this.alpha = alpha;
        this.configure(TemporalPageRank.edges, __.<Vertex>outE().asAdmin());
    }

    @Override
    public void configure(final Object... keyValues) {
        if (keyValues[0].equals(TemporalPageRank.edges)) {
            if (!(keyValues[1] instanceof Traversal))
                throw new IllegalArgumentException("TemporalPageRank.edges requires a Traversal as its argument");
            this.edgeTraversal = new PureTraversal<>(((Traversal<Vertex, Edge>) keyValues[1]).asAdmin());
            this.integrateChild(this.edgeTraversal.get());
        } else if (keyValues[0].equals(TemporalPageRank.propertyName)) {
            if (!(keyValues[1] instanceof String))
                throw new IllegalArgumentException("TemporalPageRank.propertyName requires a String as its argument");
            this.pageRankProperty = (String) keyValues[1];
        } else if (keyValues[0].equals(TemporalPageRank.times)) {
            if (!(keyValues[1] instanceof Integer))
                throw new IllegalArgumentException("TemporalPageRank.times requires an Integer as its argument");
            this.times = (int) keyValues[1];
        } else if (keyValues[0].equals(TemporalPageRank.filter)) {
            this.filter = parseFilter(keyValues[1]);
        } else if (keyValues[0].equals(TemporalPageRank.minDelay)) {
            if (!(keyValues[1] instanceof Number))
                throw new IllegalArgumentException("TemporalPageRank.minDelay requires a Number as its argument");
            this.minDelay = ((Number) keyValues[1]).longValue();
        } else if (keyValues[0].equals(TemporalPageRank.maxDelay)) {
            if (!(keyValues[1] instanceof Number))
                throw new IllegalArgumentException("TemporalPageRank.maxDelay requires a Number as its argument");
            this.maxDelay = ((Number) keyValues[1]).longValue();
        } else {
            this.parameters.set(this, keyValues);
        }
    }

    @Override
    public Parameters getParameters() {
        return this.parameters;
    }

    @Override
    public List<Traversal.Admin<Vertex, Edge>> getLocalChildren() {
        return Collections.singletonList(this.edgeTraversal.get());
    }

    @Override
    public String toString() {
        return StringFactory.stepString(this, this.edgeTraversal.get(), this.pageRankProperty, this.times, this.filter,
                new GraphFilter(this.computer));
    }

    @Override
    public TemporalPageRankVertexProgram generateProgram(final Graph graph, final Memory memory) {
        final Traversal.Admin<Vertex, Edge> detachedTraversal = this.edgeTraversal.getPure();
        detachedTraversal.setStrategies(TraversalStrategies.GlobalCache.getStrategies(graph.getClass()));
        final TemporalPageRankVertexProgram.Builder builder = TemporalPageRankVertexProgram.build()
                .property(this.pageRankProperty)
                .iterations(this.times + 1)
                .alpha(this.alpha)
                .filter(this.filter)
                .minDelay(this.minDelay)
                .maxDelay(this.maxDelay)
                .edges(detachedTraversal);
        if (this.previousTraversalVertexProgram())
            builder.initialRank(new HaltedTraversersCountTraversal());
        return builder.create(graph);
    }

    @Override
    public Set<TraverserRequirement> getRequirements() {
        return TraversalParent.super.getSelfAndChildRequirements();
    }

    @Override
    public TemporalPageRankVertexProgramStep clone() {
        final TemporalPageRankVertexProgramStep clone = (TemporalPageRankVertexProgramStep) super.clone();
        clone.edgeTraversal = this.edgeTraversal.clone();
        return clone;
    }

    @Override
    public void setTraversal(final Traversal.Admin<?, ?> parentTraversal) {
        super.setTraversal(parentTraversal);
        this.integrateChild(this.edgeTraversal.get());
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ this.edgeTraversal.hashCode() ^ this.pageRankProperty.hashCode() ^ this.times
                ^ this.filter.hashCode() ^ Long.hashCode(this.minDelay) ^ Long.hashCode(this.maxDelay);
    }

    private static TemporalPathFilterStep.TemporalPathType parseFilter(final Object filter) {
        if (filter instanceof TemporalPathFilterStep.TemporalPathType)
            return (TemporalPathFilterStep.TemporalPathType) filter;
        if (filter instanceof String) {
            try {
                return TemporalPathFilterStep.TemporalPathType.valueOf(((String) filter).trim().toUpperCase(Locale.ENGLISH));
            } catch (final IllegalArgumentException ignored) {
                throw new IllegalArgumentException("Unsupported TemporalPageRank.filter value: " + filter);
            }
        }

        throw new IllegalArgumentException("TemporalPageRank.filter requires a TemporalPathType or String as its argument");
    }
}
