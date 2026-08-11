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
package org.apache.tinkerpop.gremlin.util;

import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.util.wrapped.WrappedEdge;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LifetimeHelperTest {

    @Test
    public void shouldTreatEdgeWithLiveEndpointsAsAlive() {
        final Edge edge = edgeWithLifetime(10L, 20L,
                elementWithLifetime(0L, 30L), elementWithLifetime(10L, 20L));

        assertTrue(LifetimeHelper.isAliveDuring(edge, Lifetime.from(10L, 20L)));
    }

    @Test
    public void shouldRejectEdgeWithDeadOutVertex() {
        final Edge edge = edgeWithLifetime(10L, 20L,
                elementWithLifetime(0L, 9L), elementWithLifetime(10L, 20L));

        assertFalse(LifetimeHelper.isAliveDuring(edge, Lifetime.from(10L, 20L)));
    }

    @Test
    public void shouldRejectEdgeWithDeadInVertex() {
        final Edge edge = edgeWithLifetime(10L, 20L,
                elementWithLifetime(10L, 20L), elementWithLifetime(21L, 30L));

        assertFalse(LifetimeHelper.isAliveDuring(edge, Lifetime.from(10L, 20L)));
    }

    @Test
    public void shouldNormalizeMissingAndNullEndpointBounds() {
        final Edge edge = edgeWithLifetime(10L, 20L,
                elementWithLifetime(null, null), elementWithNullLifetime());

        assertTrue(LifetimeHelper.isAliveDuring(edge, Lifetime.from(10L, 20L)));
    }

    @Test
    public void shouldIncludeBoundaryIntersections() {
        final Edge edge = edgeWithLifetime(20L, 30L,
                elementWithLifetime(20L, 30L), elementWithLifetime(20L, 30L));

        assertTrue(LifetimeHelper.isAliveDuring(edge, Lifetime.from(10L, 20L)));
    }

    @Test
    public void shouldRejectWrappedEdgeWithDeadUnderlyingOutVertex() {
        final Edge baseEdge = edgeWithLifetime(10L, 20L,
                elementWithLifetime(0L, 9L), elementWithLifetime(10L, 20L));
        final Edge wrappedEdge = wrappedEdgeWithLifetime(baseEdge, 10L, 20L,
                elementWithLifetime(10L, 20L), elementWithLifetime(10L, 20L));

        assertFalse(LifetimeHelper.isAliveDuring(wrappedEdge, Lifetime.from(10L, 20L)));
    }

    @Test
    public void shouldRejectNestedWrappedEdgeWithDeadUnderlyingInVertex() {
        final Edge baseEdge = edgeWithLifetime(10L, 20L,
                elementWithLifetime(10L, 20L), elementWithLifetime(21L, 30L));
        final Edge innerWrappedEdge = wrappedEdgeWithLifetime(baseEdge, 10L, 20L,
                elementWithLifetime(10L, 20L), elementWithLifetime(10L, 20L));
        final Edge outerWrappedEdge = wrappedEdgeWithLifetime(innerWrappedEdge, 10L, 20L,
                elementWithLifetime(10L, 20L), elementWithLifetime(10L, 20L));

        assertFalse(LifetimeHelper.isAliveDuring(outerWrappedEdge, Lifetime.from(10L, 20L)));
    }

    @Test
    public void shouldAcceptNestedWrappedEdgeWhenEdgeAndUnderlyingEndpointsOverlap() {
        final Edge baseEdge = edgeWithLifetime(10L, 20L,
                elementWithLifetime(0L, 10L), elementWithLifetime(20L, 30L));
        final Edge innerWrappedEdge = wrappedEdgeWithLifetime(baseEdge, 10L, 20L,
                elementWithLifetime(21L, 30L), elementWithLifetime(0L, 9L));
        final Edge outerWrappedEdge = wrappedEdgeWithLifetime(innerWrappedEdge, 10L, 20L,
                elementWithLifetime(21L, 30L), elementWithLifetime(0L, 9L));

        assertTrue(LifetimeHelper.isAliveDuring(outerWrappedEdge, Lifetime.from(10L, 20L)));
    }

    private static Edge edgeWithLifetime(final Object start, final Object end,
                                         final Element outVertex, final Element inVertex) {
        final Edge edge = mock(Edge.class);
        setLifetimeProperties(edge, start, end);
        when(edge.outVertex()).thenReturn((Vertex) outVertex);
        when(edge.inVertex()).thenReturn((Vertex) inVertex);
        return edge;
    }

    private static Edge wrappedEdgeWithLifetime(final Edge baseEdge, final Object start, final Object end,
                                                final Element outVertex, final Element inVertex) {
        final TestWrappedEdge edge = mock(TestWrappedEdge.class);
        setLifetimeProperties(edge, start, end);
        when(edge.getBaseEdge()).thenReturn(baseEdge);
        when(edge.outVertex()).thenReturn((Vertex) outVertex);
        when(edge.inVertex()).thenReturn((Vertex) inVertex);
        return edge;
    }

    private static Vertex elementWithLifetime(final Object start, final Object end) {
        final Vertex vertex = mock(Vertex.class);
        setLifetimeProperties(vertex, start, end);
        return vertex;
    }

    private static Vertex elementWithNullLifetime() {
        final Vertex vertex = mock(Vertex.class);
        final VertexProperty<Object> nullProperty = mock(VertexProperty.class);
        when(nullProperty.orElse(null)).thenReturn(null);
        when(vertex.property(Lifetime.START_TIME)).thenReturn(nullProperty);
        when(vertex.property(Lifetime.END_TIME)).thenReturn(nullProperty);
        return vertex;
    }

    private static void setLifetimeProperties(final Element element, final Object start, final Object end) {
        final VertexProperty<Object> startProperty = propertyOrEmpty(start);
        final VertexProperty<Object> endProperty = propertyOrEmpty(end);
        when(element.property(Lifetime.START_TIME)).thenReturn(startProperty);
        when(element.property(Lifetime.END_TIME)).thenReturn(endProperty);
    }

    private static VertexProperty<Object> propertyOrEmpty(final Object value) {
        final VertexProperty<Object> property = mock(VertexProperty.class);
        when(property.orElse(null)).thenReturn(value);
        return property;
    }

    private interface TestWrappedEdge extends Edge, WrappedEdge<Edge> {
    }
}
