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

import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.wrapped.WrappedVertex;
import org.apache.tinkerpop.gremlin.util.LifetimeHelper;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

import java.util.Collections;
import java.util.Date;
import java.util.Iterator;

/**
 * A {@link Vertex} decorator that constrains all graph-navigation and property access
 * to elements alive at a specific snapshot instant.
 *
 * <p>When an {@link org.apache.tinkerpop.gremlin.process.traversal.step.filter.AtTimeStep}
 * passes a vertex, it wraps it in a {@code TemporalVertex}. Every subsequent step that calls
 * {@link Vertex#vertices}, {@link Vertex#edges}, or {@link Vertex#properties} on the object
 * in the traverser receives temporally-filtered results automatically — no step rewriting
 * required.</p>
 *
 * <p>The temporal context propagates forward: {@code vertices()} returns {@code TemporalVertex}
 * instances, and {@code edges()} returns {@link TemporalEdge} instances, so the filtering
 * is inherited through arbitrarily deep graph navigation.</p>
 *
 * @author Alex Spitalas
 */
public final class TemporalVertex implements Vertex, WrappedVertex<Vertex> {

    private final Vertex base;
    private final Lifetime lifetime;
    private final boolean filterAdjacentElementsForNavigation;

    public TemporalVertex(final Vertex base, final Date instant) {
        this(base, Lifetime.from(instant, instant));
    }

    public TemporalVertex(final Vertex base, final Date instant, final boolean filterAdjacentElementsForNavigation) {
        this(base, Lifetime.from(instant, instant), filterAdjacentElementsForNavigation);
    }

    public TemporalVertex(final Vertex base, final Date startInstant, final Date endInstant) {
        this(base, Lifetime.from(startInstant, endInstant));
    }

    public TemporalVertex(final Vertex base, final Lifetime lifetime) {
        this(base, lifetime, true);
    }

    public TemporalVertex(final Vertex base, final Lifetime lifetime, final boolean filterAdjacentElementsForNavigation) {
        this.base    = base;
        this.lifetime = lifetime;
        this.filterAdjacentElementsForNavigation = filterAdjacentElementsForNavigation;
    }

    // ── WrappedVertex ────────────────────────────────────────────────────

    @Override
    public Vertex getBaseVertex() {
        return base;
    }

    public Date getInstant() {
        return lifetime.getStartDate();
    }

    public Date getStartInstant() {
        return lifetime.getStartDate();
    }

    public Date getEndInstant() {
        return lifetime.getEndDate();
    }

    public boolean filtersAdjacentElementsForNavigation() {
        return filterAdjacentElementsForNavigation;
    }

    // ── Graph Navigation (the critical overrides) ─────────────────────────

    /**
     * Returns only edges that are alive at the snapshot instant, wrapped in
     * {@link TemporalEdge} so that {@code inV()}/{@code outV()} also return
     * {@link TemporalVertex} instances.
     */
    @Override
    public Iterator<Edge> edges(final Direction direction, final String... edgeLabels) {
        return IteratorUtils.map(
            IteratorUtils.filter(
                base.edges(direction, edgeLabels),
                edge -> filterAdjacentElementsForNavigation ?
                        LifetimeHelper.isVisibleDuring(edge, lifetime) :
                        LifetimeHelper.isAliveDuring(edge, lifetime)
            ),
            edge -> (Edge) new TemporalEdge(edge, lifetime)
        );
    }

    /**
     * Returns only adjacent vertices reachable via edges that are alive at the
     * snapshot instant AND whose own lifetime contains the instant.
     * Each returned vertex is a {@link TemporalVertex} carrying the same instant.
     */
    @Override
    public Iterator<Vertex> vertices(final Direction direction, final String... edgeLabels) {
        return IteratorUtils.flatMap(
            base.edges(direction, edgeLabels),
            edge -> {
                if (!LifetimeHelper.isAliveDuring(edge, lifetime))
                    return Collections.emptyIterator();

                final Vertex neighbour = neighbourVertex(edge, direction);
                if (filterAdjacentElementsForNavigation && !LifetimeHelper.isAliveDuring(neighbour, lifetime))
                    return Collections.emptyIterator();

                return IteratorUtils.of(new TemporalVertex(neighbour, lifetime));
            }
        );
    }

    // ── Property Access ───────────────────────────────────────────────────

    /**
     * Returns only vertex-properties alive at the snapshot instant, each wrapped in a
     * {@link TemporalVertexProperty} so that {@code element()} navigates back to a
     * {@link TemporalVertex}.
     *
     * <p>Because {@code HasContainer.test(element)} calls {@code element.properties(key)}
     * and iterates the result, this override makes {@code has()}, {@code values()}, and
     * {@code properties()} all temporally correct without any strategy rewriting.</p>
     */
    @Override
    @SuppressWarnings("unchecked")
    public <V> Iterator<VertexProperty<V>> properties(final String... propertyKeys) {
        return IteratorUtils.map(
            IteratorUtils.filter(
                base.properties(propertyKeys),
                prop -> LifetimeHelper.isAliveDuring(prop, lifetime)
            ),
            prop -> (VertexProperty<V>) new TemporalVertexProperty<>(prop, lifetime)
        );
    }

    @Override
    public <V> VertexProperty<V> property(final String key) {
        final Iterator<VertexProperty<V>> alive = properties(key);
        return alive.hasNext() ? alive.next() : VertexProperty.empty();
    }

    @Override
    public <V> VertexProperty<V> property(final VertexProperty.Cardinality cardinality,
                                           final String key, final V value,
                                           final Object... keyValues) {
        return base.property(cardinality, key, value, keyValues);
    }

    // ── Identity ─────────────────────────────────────────────────────────

    @Override public Object id()    { return base.id(); }
    @Override public String label() { return base.label(); }
    @Override public Graph graph()  { return base.graph(); }

    // ── Mutation — delegate to base ───────────────────────────────────────

    @Override
    public Edge addEdge(final String label, final Vertex inVertex, final Object... keyValues) {
        return base.addEdge(label, inVertex, keyValues);
    }

    @Override
    public void remove() {
        base.remove();
    }

    // ── Object ────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        return "temporal[" + base.toString() + "@(" + lifetime.getStartDate() + "," + lifetime.getEndDate() + ")]";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o instanceof TemporalVertex) return base.equals(((TemporalVertex) o).base);
        return base.equals(o);
    }

    @Override
    public int hashCode() {
        return base.hashCode();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Vertex neighbourVertex(final Edge edge, final Direction direction) {
        switch (direction) {
            case OUT:  return edge.inVertex();
            case IN:   return edge.outVertex();
            default:   // BOTH
                return base.equals(edge.outVertex()) ? edge.inVertex() : edge.outVertex();
        }
    }
}
