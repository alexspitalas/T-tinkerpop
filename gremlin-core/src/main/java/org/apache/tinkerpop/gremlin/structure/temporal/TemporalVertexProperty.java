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

import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;

import java.util.Date;
import java.util.Iterator;

/**
 * A {@link VertexProperty} decorator that:
 * <ul>
 *   <li>Returns a {@link TemporalVertex} from {@link #element()} so that navigating
 *       back to the owning vertex preserves the temporal scope.</li>
 *   <li>Delegates meta-property access to the base (meta-properties do not carry
 *       independent temporal metadata in this model).</li>
 * </ul>
 *
 * <p>Instances are produced by {@link TemporalVertex#properties(String...)} and by
 * {@link org.apache.tinkerpop.gremlin.process.traversal.step.filter.AtTimeStep} when
 * a {@link VertexProperty} passes through the snapshot filter.</p>
 *
 * @author Alex Spitalas
 */
public final class TemporalVertexProperty<V> implements VertexProperty<V> {

    private final VertexProperty<V> base;
    private final Lifetime lifetime;

    public TemporalVertexProperty(final VertexProperty<V> base, final Date instant) {
        this(base, instant, instant);
    }

    public TemporalVertexProperty(final VertexProperty<V> base, final Date startInstant, final Date endInstant) {
        this.base    = base;
        this.lifetime = Lifetime.from(startInstant, endInstant);
    }

    public TemporalVertexProperty(final VertexProperty<V> base, final Lifetime lifetime) {
        this.base    = base;
        this.lifetime = lifetime;
    }

    public VertexProperty<V> getBaseVertexProperty() { return base; }
    public Date              getInstant()            { return lifetime.getStartDate(); }
    public Date              getStartInstant()       { return lifetime.getStartDate(); }
    public Date              getEndInstant()         { return lifetime.getEndDate(); }

    // ── Key overrides ─────────────────────────────────────────────────────

    /** Returns the owning vertex wrapped in a {@link TemporalVertex}. */
    @Override
    public Vertex element() {
        return new TemporalVertex(base.element(), lifetime);
    }

    /**
     * Meta-properties are delegated unchanged; they do not carry independent
     * temporal metadata in the T-TinkerPop model.
     */
    @Override
    public <U> Iterator<Property<U>> properties(final String... propertyKeys) {
        return base.properties(propertyKeys);
    }

    // ── Property<V> ───────────────────────────────────────────────────────

    @Override public String  key()       { return base.key(); }
    @Override public V       value()     { return base.value(); }
    @Override public boolean isPresent() { return base.isPresent(); }

    @Override
    public <U> Property<U> property(final String key, final U value) {
        return base.property(key, value);
    }

    // ── Element ───────────────────────────────────────────────────────────

    @Override public Object id()    { return base.id(); }
    @Override public String label() { return base.label(); }
    @Override public Graph  graph() { return base.graph(); }
    @Override public void   remove(){ base.remove(); }

    // ── Object ────────────────────────────────────────────────────────────

    @Override
    public String toString() { 
        return "temporal[" + base.toString() + "@(" + lifetime.getStartDate() + "," + lifetime.getEndDate() + ")]";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o instanceof TemporalVertexProperty) return base.equals(((TemporalVertexProperty<?>) o).base);
        return base.equals(o);
    }

    @Override
    public int hashCode() { return base.hashCode(); }
}
