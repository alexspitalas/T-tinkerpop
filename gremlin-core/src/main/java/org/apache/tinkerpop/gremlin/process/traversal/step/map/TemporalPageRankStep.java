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
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalEdge;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalVertex;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.util.LifetimeHelper;

import java.util.ArrayList;
import java.util.Collections;
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

    public TemporalPageRankStep(final Traversal.Admin traversal) {
        super(traversal);
        this.barrier = (TraverserSet<S>) this.traversal.getTraverserSetSupplier().get();
    }

    @Override
    public void configure(final Object... keyValues) {
        if (keyValues[0].equals(TemporalPageRank.alpha)) {
            if (!(keyValues[1] instanceof Number))
                throw new IllegalArgumentException("TemporalPageRank.alpha requires a Number as its argument");
            this.alpha = ((Number) keyValues[1]).doubleValue();
        } else if (keyValues[0].equals(TemporalPageRank.beta)) {
            if (!(keyValues[1] instanceof Number))
                throw new IllegalArgumentException("TemporalPageRank.beta requires a Number as its argument");
            this.beta = ((Number) keyValues[1]).doubleValue();
        } else if (keyValues[0].equals(TemporalPageRank.propertyName)) {
            if (!(keyValues[1] instanceof String))
                throw new IllegalArgumentException("TemporalPageRank.propertyName requires a String as its argument");
            this.propertyName = (String) keyValues[1];
        } else if (keyValues[0].equals(TemporalPageRank.normalize)) {
            if (!(keyValues[1] instanceof Boolean))
                throw new IllegalArgumentException("TemporalPageRank.normalize requires a Boolean as its argument");
            this.normalize = (Boolean) keyValues[1];
        } else {
            this.parameters.set(null, keyValues);
        }
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

        while (this.starts.hasNext()) {
            final Traverser.Admin<S> traverser = this.starts.next();
            if (!(traverser.get() instanceof Vertex))
                throw new IllegalArgumentException("temporalPageRank() can only be applied to vertex traversers");
            final Vertex vertex = (Vertex) traverser.get();
            scopedVertices.putIfAbsent(vertex.id(), baseVertex(vertex));
            traverser.setStepId(this.getNextStep().getId());
            this.barrier.add(traverser);
        }

        if (this.barrier.isEmpty())
            return;

        validateConfiguration();
        final List<TemporalEdge> edges = readTemporalEdges(scopedVertices);

        final Map<Object, Double> ranks = TemporalPageRankAlgorithm.execute(edges, alpha, beta, normalize);
        writeRanks(scopedVertices.values(), propertyName, ranks);
    }

    private void validateConfiguration() {
        if (null == propertyName || propertyName.trim().isEmpty())
            throw new IllegalArgumentException("Temporal PageRank propertyName must be a non-empty String");
    }

    private List<TemporalEdge> readTemporalEdges(final Map<Object, Vertex> scopedVertices) {
        final List<TemporalEdge> temporalEdges = new ArrayList<>();
        final Set<Object> scopedVertexIds = new HashSet<>(scopedVertices.keySet());

        for (final Vertex vertex : scopedVertices.values()) {
            final Iterator<Edge> edges = vertex.edges(Direction.OUT);
            try {
                while (edges.hasNext()) {
                    final TemporalEdge temporalEdge = new TemporalEdge(edges.next());

                    if (LifetimeHelper.isAliveDuring(temporalEdge.getBaseEdge(), temporalEdge.getLifetime()) &&
                            scopedVertexIds.contains(temporalEdge.outVertex().id()) &&
                            scopedVertexIds.contains(temporalEdge.inVertex().id()))
                        temporalEdges.add(temporalEdge);
                }
            } finally {
                CloseableIterator.closeIterator(edges);
            }
        }

        return temporalEdges;
    }

    private Vertex baseVertex(final Vertex vertex) {
        if (vertex instanceof TemporalVertex)
            return ((TemporalVertex) vertex).getBaseVertex();
        return vertex;
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
                this.propertyName.hashCode() ^ Boolean.hashCode(this.normalize);
    }

    @Override
    public String toString() {
        return StringFactory.stepString(this, this.alpha, this.beta, this.propertyName, this.normalize);
    }
}
