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
package org.apache.tinkerpop.gremlin.process.traversal.step.filter;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalEdge;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalVertex;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalVertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.util.LifetimeHelper;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;

import java.util.Date;
import java.util.Objects;

/**
 * A filter step that restricts the traversal to elements alive at a given point in time.
 *
 * <p>For each {@link Element} in the traverser, this step:
 * <ol>
 *   <li>Drops it if {@code !isAliveAt(element, instant)}.</li>
 *   <li>Wraps passing {@link Vertex}, {@link Edge}, and {@link VertexProperty} elements
 *       in {@link TemporalVertex}, {@link TemporalEdge}, and {@link TemporalVertexProperty}
 *       respectively. These decorators propagate the temporal scope through all subsequent
 *       graph navigation without any strategy step-rewriting.</li>
 * </ol>
 * </p>
 *
 * <p>Non-element values (Strings, Numbers, Maps, etc.) pass through unchanged.</p>
 */
public final class AtTimeStep<S> extends FilterStep<S> {

    private final Date instant;

    public AtTimeStep(final Traversal.Admin traversal, final Object instant) {
        super(traversal);
        if (null == instant)
            throw new IllegalArgumentException("Temporal instant cannot be null");
        this.instant = Lifetime.toTemporalDate(instant);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected boolean filter(final Traverser.Admin<S> traverser) {
        final S current = traverser.get();

        // Non-element values pass through unchanged.
        if (!(current instanceof Element))
            return true;
        // Drop elements not alive at this instant.
        if (!LifetimeHelper.isAliveAt((Element) current, instant))
            return false;

        // Wrap passing elements so subsequent navigation respects the snapshot.
        // Note: check VertexProperty before Vertex/Edge (it's neither a Vertex nor Edge,
        // but the ordering avoids ambiguity on future subclass hierarchies).
        if (current instanceof VertexProperty) {
            if (!(current instanceof TemporalVertexProperty)
                    || !((TemporalVertexProperty<?>) current).getInstant().equals(instant)) {
                final VertexProperty<?> base = (current instanceof TemporalVertexProperty)
                        ? ((TemporalVertexProperty<?>) current).getBaseVertexProperty()
                        : (VertexProperty<?>) current;
                traverser.set((S) new TemporalVertexProperty<>(base, instant));
            }
        } else if (current instanceof Vertex) {
            if (!(current instanceof TemporalVertex)
                    || !((TemporalVertex) current).getInstant().equals(instant)) {
                final Vertex base = (current instanceof TemporalVertex)
                        ? ((TemporalVertex) current).getBaseVertex()
                        : (Vertex) current;
                traverser.set((S) new TemporalVertex(base, instant));
            }
        } else if (current instanceof Edge) {
            if (!(current instanceof TemporalEdge)
                    || !((TemporalEdge) current).getInstant().equals(instant)) {
                final Edge base = (current instanceof TemporalEdge)
                        ? ((TemporalEdge) current).getBaseEdge()
                        : (Edge) current;
                traverser.set((S) new TemporalEdge(base, instant));
            }
        }

        return true;
    }

    public Date getInstant() { return instant; }

    @Override
    public String toString() { return StringFactory.stepString(this, instant); }

    @Override
    public int hashCode() { return Objects.hash(super.hashCode(), instant); }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof AtTimeStep)) return false;
        return super.equals(o) && instant.equals(((AtTimeStep<?>) o).instant);
    }

    /**
     * {@code instant} is {@code final} and treated as effectively immutable within this class
     * (we never call {@link Date#setTime}), so the default bitwise clone from
     * {@link org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep#clone()}
     * is safe.
     */
    @Override
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public AtTimeStep<S> clone() {
        return (AtTimeStep<S>) super.clone();
    }
}
