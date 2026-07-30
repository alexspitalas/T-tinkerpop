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
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.wrapped.WrappedEdge;
import org.apache.tinkerpop.gremlin.util.LifetimeHelper;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

import java.util.Date;
import java.util.Iterator;

/**
 * An {@link Edge} decorator that:
 * <ul>
 *   <li>Wraps {@code inVertex()} and {@code outVertex()} in {@link TemporalVertex} so the
 *       temporal context propagates through {@code outE().inV()} chains.</li>
 *   <li>Filters {@code properties()} to only those alive at the snapshot instant.</li>
 * </ul>
 *
 * <p>Instances are produced by {@link TemporalVertex#edges} and by
 * {@link org.apache.tinkerpop.gremlin.process.traversal.step.filter.AtTimeStep} when the
 * current element in the traverser is an {@link Edge}.</p>
 *
 * @author Alex Spitalas
 */
public final class TemporalEdge implements Edge, WrappedEdge<Edge> {

    private final Edge base;
    private final Lifetime lifetime;

    public TemporalEdge(final Edge base) {
        this(base, Lifetime.fromProperties(base));
    }

    public TemporalEdge(final Edge base, final Date instant) {
        this(base, instant, instant);
    }

    public TemporalEdge(final Edge base, final Date startInstant, final Date endInstant) {
        this.base    = base;
        this.lifetime = Lifetime.from(startInstant, endInstant);
    }

    public TemporalEdge(final Edge base, final Lifetime lifetime) {
        this.base    = base;
        this.lifetime = lifetime;
    }

    // ── WrappedEdge ───────────────────────────────────────────────────────

    @Override
    public Edge getBaseEdge() {
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

    public Lifetime getLifetime() {
        return lifetime;
    }

    // ── Graph Navigation ──────────────────────────────────────────────────

    /**
     * Returns the in-vertex wrapped in a {@link TemporalVertex} so that subsequent
     * steps (e.g. {@code .has()}, {@code .out()}) are still temporally scoped.
     */
    @Override
    public Vertex inVertex() {
        return new TemporalVertex(base.inVertex(), lifetime);
    }

    /**
     * Returns the out-vertex wrapped in a {@link TemporalVertex}.
     */
    @Override
    public Vertex outVertex() {
        return new TemporalVertex(base.outVertex(), lifetime);
    }

    /**
     * Returns both vertices (for {@code bothV()} / {@code EdgeVertexStep}) wrapped in
     * {@link TemporalVertex} instances, filtering out any that are not alive at the instant.
     */
    @Override
    public Iterator<Vertex> vertices(final Direction direction) {
        return IteratorUtils.filter(
            IteratorUtils.map(base.vertices(direction),
                v -> (Vertex) new TemporalVertex(v, lifetime)),
            v -> LifetimeHelper.isAliveDuring(((TemporalVertex) v).getBaseVertex(), lifetime)
        );
    }

    // ── Property Access ───────────────────────────────────────────────────

    /**
     * Edge properties are plain {@code Property<V>} (not {@code Element}), so they
     * do not carry independent temporal metadata in this model. Delegated unchanged.
     */
    @Override
    public <V> Iterator<Property<V>> properties(final String... propertyKeys) {
        return base.properties(propertyKeys);
    }

    // ── Identity ─────────────────────────────────────────────────────────

    @Override public Object id()    { return base.id(); }
    @Override public String label() { return base.label(); }
    @Override public Graph graph()  { return base.graph(); }

    // ── Mutation — delegate to base ───────────────────────────────────────

    @Override
    public void remove() {
        base.remove();
    }

    @Override
    public <V> Property<V> property(final String key, final V value) {
        return base.property(key, value);
    }

    // ── Object ────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        return "temporal[" + base.toString() + "@(" + lifetime.getStartDate() + "," + lifetime.getEndDate() + ")]";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o instanceof TemporalEdge) return base.equals(((TemporalEdge) o).base);
        return base.equals(o);
    }

    @Override
    public int hashCode() {
        return base.hashCode();
    }
}
