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

import org.apache.tinkerpop.gremlin.process.computer.Computer;
import org.apache.tinkerpop.gremlin.process.computer.GraphFilter;
import org.apache.tinkerpop.gremlin.process.computer.Memory;
import org.apache.tinkerpop.gremlin.process.computer.ranking.pagerank.PageRankVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.traversal.lambda.HaltedTraversersCountTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.Configuring;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.Parameters;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.util.AllenStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.PureTraversal;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.structure.util.star.StarGraph;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Temporal PageRank that projects the graph through a query-window reference element built by executing
 * {@code lifetime(...)} on a temporary vertex.
 */
public final class TemporalPageRankVertexProgramStep extends VertexProgramStep implements TraversalParent, Configuring {

    private Parameters parameters = new Parameters();
    private PureTraversal<Vertex, Edge> edgeTraversal;
    private String pageRankProperty = PageRankVertexProgram.PAGE_RANK;
    private int times = 20;
    private final double alpha;
    private final String queryStartTime;
    private final String queryEndTime;

    public TemporalPageRankVertexProgramStep(final Traversal.Admin traversal, final double alpha,
                                             final String queryStartTime, final String queryEndTime) {
        super(traversal);
        if (null == queryStartTime)
            throw new IllegalArgumentException("Start time cannot be null");
        this.alpha = alpha;
        this.queryStartTime = queryStartTime;
        this.queryEndTime = queryEndTime;
        this.configure(PageRank.edges, __.<Vertex>outE().asAdmin());
    }

    @Override
    public void configure(final Object... keyValues) {
        if (keyValues[0].equals(PageRank.edges)) {
            if (!(keyValues[1] instanceof Traversal))
                throw new IllegalArgumentException("PageRank.edges requires a Traversal as its argument");
            this.edgeTraversal = new PureTraversal<>(((Traversal<Vertex, Edge>) keyValues[1]).asAdmin());
            this.integrateChild(this.edgeTraversal.get());
        } else if (keyValues[0].equals(PageRank.propertyName)) {
            if (!(keyValues[1] instanceof String))
                throw new IllegalArgumentException("PageRank.propertyName requires a String as its argument");
            this.pageRankProperty = (String) keyValues[1];
        } else if (keyValues[0].equals(PageRank.times)) {
            if (!(keyValues[1] instanceof Integer))
                throw new IllegalArgumentException("PageRank.times requires an Integer as its argument");
            this.times = (int) keyValues[1];
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
    public Computer getComputer() {
        final TemporalWindowReference windowReference = this.createWindowReference();
        final Computer baseComputer = super.getComputer();
        return baseComputer
                .vertices(this.buildComputerVertexFilter(baseComputer, windowReference.referenceElement))
                .edges(this.buildComputerEdgeFilter(baseComputer, windowReference.referenceElement));
    }

    @Override
    public String toString() {
        final TemporalWindowReference windowReference = this.createWindowReference();
        return StringFactory.stepString(this, this.edgeTraversal.get(), this.pageRankProperty, this.times,
                windowReference.effectiveStartTime, windowReference.effectiveEndTime, new GraphFilter(this.getComputer()));
    }

    @Override
    public PageRankVertexProgram generateProgram(final Graph graph, final Memory memory) {
        final TemporalWindowReference windowReference = this.createWindowReference();
        final Traversal.Admin<Vertex, Edge> filteredTraversal =
                this.appendActiveWindowFilters(this.edgeTraversal.getPure(), windowReference.referenceElement);
        filteredTraversal.setStrategies(TraversalStrategies.GlobalCache.getStrategies(graph.getClass()));

        final PageRankVertexProgram.Builder builder = PageRankVertexProgram.build()
                .property(this.pageRankProperty)
                .iterations(this.times + 1)
                .alpha(this.alpha)
                .edges(filteredTraversal);
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
        return super.hashCode() ^ this.edgeTraversal.hashCode() ^ this.pageRankProperty.hashCode() ^ this.times ^
                this.queryStartTime.hashCode() ^ (null == this.queryEndTime ? 0 : this.queryEndTime.hashCode());
    }

    private Traversal.Admin<Vertex, Vertex> buildComputerVertexFilter(final Computer baseComputer,
                                                                      final Element referenceElement) {
        final Traversal<Vertex, Vertex> existingTraversal = baseComputer.getVertices();
        final Traversal.Admin<Vertex, Vertex> baseTraversal = null == existingTraversal ?
                __.<Vertex>start().asAdmin() :
                existingTraversal.asAdmin().clone();
        return this.appendActiveWindowFilters(baseTraversal, referenceElement);
    }

    private Traversal.Admin<Vertex, Edge> buildComputerEdgeFilter(final Computer baseComputer,
                                                                  final Element referenceElement) {
        final Traversal<Vertex, Edge> existingTraversal = baseComputer.getEdges();
        final Traversal.Admin<Vertex, Edge> baseTraversal = null == existingTraversal ?
                __.<Vertex>bothE().asAdmin() :
                existingTraversal.asAdmin().clone();
        return this.appendActiveWindowFilters(baseTraversal, referenceElement);
    }

    private <S, E extends Element> Traversal.Admin<S, E> appendActiveWindowFilters(final Traversal.Admin<S, E> traversal,
                                                                                   final Element referenceElement) {
        final GraphTraversal.Admin<S, E> graphTraversal = (GraphTraversal.Admin<S, E>) traversal;
        graphTraversal.not(this.createAllenRelationTraversal(AllenStep.AllenRelation.BEFORE, referenceElement));
        if (null != this.queryEndTime) {
            graphTraversal.not(this.createAllenRelationTraversal(AllenStep.AllenRelation.AFTER, referenceElement));
        }
        return graphTraversal;
    }

    private <E extends Element> Traversal.Admin<E, E> createAllenRelationTraversal(final AllenStep.AllenRelation relation,
                                                                                    final Element referenceElement) {
        return AllenStep.applyTemporalFilter(__.<E>start(), relation, referenceElement).asAdmin();
    }

    private TemporalWindowReference createWindowReference() {
        final StarGraph windowGraph = StarGraph.open();
        final Vertex referenceVertex = windowGraph.addVertex(T.label, "temporalPageRankWindow");

        windowGraph.traversal().V(referenceVertex.id()).lifetime(this.queryStartTime, this.queryEndTime).iterate();

        final String effectiveStartTime = referenceVertex.value("startTime");
        final String effectiveEndTime = referenceVertex.value("endTime");
        return new TemporalWindowReference(referenceVertex, effectiveStartTime, effectiveEndTime);
    }

    private static final class TemporalWindowReference {
        private final Element referenceElement;
        private final String effectiveStartTime;
        private final String effectiveEndTime;

        private TemporalWindowReference(final Element referenceElement, final String effectiveStartTime,
                                        final String effectiveEndTime) {
            this.referenceElement = referenceElement;
            this.effectiveStartTime = effectiveStartTime;
            this.effectiveEndTime = effectiveEndTime;
        }
    }
}
