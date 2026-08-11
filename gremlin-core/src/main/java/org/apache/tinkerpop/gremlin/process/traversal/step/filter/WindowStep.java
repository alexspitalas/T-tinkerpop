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
 * A filter step that restricts the traversal to elements whose lifetime intersects a given window.
 *
 * <p>For each {@link Element} in the traverser, this step:
 * <ol>
 *   <li>Drops it if {@code !isAliveDuring(element, windowStart, windowEnd)}.</li>
 *   <li>Wraps passing {@link Vertex}, {@link Edge}, and {@link VertexProperty} elements
 *       in {@link TemporalVertex}, {@link TemporalEdge}, and {@link TemporalVertexProperty}
 *       respectively. These decorators propagate the temporal scope through all subsequent
 *       graph navigation without any strategy step-rewriting.</li>
 * </ol>
 * </p>
 *
 * <p>Non-element values (Strings, Numbers, Maps, etc.) pass through unchanged.</p>
 */
public final class WindowStep<S> extends FilterStep<S> {

    private final Lifetime window;

    public WindowStep(final Traversal.Admin traversal, final Lifetime window) {
        super(traversal);
        this.window = window;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected boolean filter(final Traverser.Admin<S> traverser) {
        final S current = traverser.get();

        // Non-element values pass through unchanged.
        if (!(current instanceof Element))
            return true;
        // Drop elements whose lifetime does not intersect this window.
        if (!LifetimeHelper.isAliveDuring((Element) current, window))
            return false;

        // Wrap passing elements so subsequent navigation respects the window.
        if (current instanceof VertexProperty) {
            if (!(current instanceof TemporalVertexProperty)
                    || !window.getStartDate().equals(((TemporalVertexProperty<?>) current).getStartInstant())
                    || !window.getEndDate().equals(((TemporalVertexProperty<?>) current).getEndInstant())) {
                final VertexProperty<?> base = (current instanceof TemporalVertexProperty)
                        ? ((TemporalVertexProperty<?>) current).getBaseVertexProperty()
                        : (VertexProperty<?>) current;
                traverser.set((S) new TemporalVertexProperty<>(base, window));
            }
        } else if (current instanceof Vertex) {
            if (!(current instanceof TemporalVertex)
                    || !window.getStartDate().equals(((TemporalVertex) current).getStartInstant())
                    || !window.getEndDate().equals(((TemporalVertex) current).getEndInstant())) {
                final Vertex base = (current instanceof TemporalVertex)
                        ? ((TemporalVertex) current).getBaseVertex()
                        : (Vertex) current;
                traverser.set((S) new TemporalVertex(base, window));
            }
        } else if (current instanceof Edge) {
            if (!(current instanceof TemporalEdge)
                    || !window.getStartDate().equals(((TemporalEdge) current).getStartInstant())
                    || !window.getEndDate().equals(((TemporalEdge) current).getEndInstant())) {
                final Edge base = (current instanceof TemporalEdge)
                        ? ((TemporalEdge) current).getBaseEdge()
                        : (Edge) current;
                traverser.set((S) new TemporalEdge(base, window));
            }
        }

        return true;
    }

    public Date getWindowStart() { return window.getStartDate(); }
    public Date getWindowEnd() { return window.getEndDate(); }

    @Override
    public String toString() { return StringFactory.stepString(this, window); }

    @Override
    public int hashCode() { return Objects.hash(super.hashCode(), window); }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof WindowStep)) return false;
        final WindowStep<?> that = (WindowStep<?>) o;
        return super.equals(o) && window.equals(that.window);
    }

    @Override
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public WindowStep<S> clone() {
        return (WindowStep<S>) super.clone();
    }
}
