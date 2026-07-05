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
package org.apache.tinkerpop.gremlin.structure.temporal;

import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalVertex;
import org.apache.tinkerpop.gremlin.structure.util.wrapped.WrappedGraph;
import org.apache.tinkerpop.gremlin.util.LifetimeHelper;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

import java.util.Date;
import java.util.Iterator;

/**
 * A read-only {@link Graph} decorator used exclusively for OLAP algorithms (PageRank,
 * ShortestPath, etc.) to ensure they operate on a temporal snapshot of the graph.
 *
 * <p>{@link org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.VertexProgramStep}
 * retrieves the graph via {@code traversal.getGraph().get()} and feeds it to the
 * {@link org.apache.tinkerpop.gremlin.process.computer.GraphComputer}. By replacing the
 * traversal's graph reference with a {@code TemporalGraphView}, all OLAP algorithms
 * transparently operate on only the elements alive at the given instant.</p>
 *
 * <p>This class is set on the traversal by
 * {@link org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.TemporalGraphViewStrategy}
 * during the strategy-application (compile) phase.</p>
 *
 * @author Alex Spitalas
 */
public final class TemporalGraphView implements Graph, WrappedGraph<Graph> {

    private final Graph base;
    private final Date  instant;

    public TemporalGraphView(final Graph base, final Date instant) {
        this.base    = base;
        this.instant = instant;
    }

    // ── WrappedGraph ──────────────────────────────────────────────────────

    @Override
    public Graph getBaseGraph() {
        return base;
    }

    public Date getInstant() {
        return instant;
    }

    // ── Filtered iteration ────────────────────────────

    @Override
    public Iterator<Vertex> vertices(final Object... ids) {
        // Wrap in TemporalVertex so OLAP algorithms that call vertex.edges() internally
        // also receive temporally-filtered adjacency — not just the seed set.
        return IteratorUtils.map(
            IteratorUtils.filter(base.vertices(ids), v -> LifetimeHelper.isAliveAt(v, instant)),
            v -> (Vertex) new TemporalVertex(v, instant)
        );
    }

    @Override
    public Iterator<Edge> edges(final Object... ids) {
        return IteratorUtils.filter(
            base.edges(ids),
            e -> LifetimeHelper.isAliveAt(e, instant)
        );
    }

    // ── Delegation ────────────────────────────────────────────────────────

    @Override
    public Features features() {
        return base.features();
    }

    @Override
    public Variables variables() {
        return base.variables();
    }

    @Override
    public Configuration configuration() {
        return base.configuration();
    }

    @Override
    public Transaction tx() {
        return base.tx();
    }

    @Override
    public void close() {
        // Do NOT close the underlying graph — we are just a view.
    }

    // ── Mutation — unsupported on a view ─────────────────────────────────

    @Override
    public Vertex addVertex(final Object... keyValues) {
        throw new UnsupportedOperationException("TemporalGraphView is read-only");
    }

    @Override
    public <C extends GraphComputer> C compute(final Class<C> graphComputerClass) throws IllegalArgumentException {
        return base.compute(graphComputerClass);
    }

    @Override
    public GraphComputer compute() throws IllegalArgumentException {
        return base.compute();
    }
}
