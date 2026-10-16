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
package org.apache.tinkerpop.gremlin.tinkergraph.structure;

import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalEdge;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalElement;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalVertex;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalVertexProperty;
import org.junit.Test;

import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests verifying that {@link TemporalElement} in-memory lifetime caching, invalidation,
 * cloning, and decorator wrapping behave correctly and deterministically.
 */
public class TemporalElementTest {

    @Test
    public void shouldCacheLifetimeOnTinkerVertex() {
        final TinkerGraph graph = TinkerGraph.open();
        final TinkerVertex v = (TinkerVertex) graph.addVertex("name", "marko");
        final Lifetime lifetime = Lifetime.from(1000L, 2000L);
        lifetime.attachTo(v);

        // Directly attached lifetime should be cached
        final Lifetime cached = v.getLifetime();
        assertNotNull(cached);
        assertEquals(new Date(1000L), cached.getStartDate());
        assertEquals(new Date(2000L), cached.getEndDate());

        // Repeated lookups must return the exact same instance in O(1)
        assertSame(cached, v.getLifetime());
        assertSame(cached, Lifetime.fromProperties(v));
    }

    @Test
    public void shouldInvalidateCacheOnVertexPropertyChange() {
        final TinkerGraph graph = TinkerGraph.open();
        final TinkerVertex v = (TinkerVertex) graph.addVertex();
        final Lifetime lifetime = Lifetime.from(1000L, 2000L);
        lifetime.attachTo(v);

        final Lifetime firstLifetime = v.getLifetime();
        assertSame(firstLifetime, v.getLifetime());

        // Modifying temporal property directly must invalidate cache
        v.property(Lifetime.TEMPORAL_INTERVALS, "3000:4000");

        final Lifetime secondLifetime = v.getLifetime();
        assertNotSame(firstLifetime, secondLifetime);
        assertEquals(new Date(3000L), secondLifetime.getStartDate());
        assertEquals(new Date(4000L), secondLifetime.getEndDate());
    }

    @Test
    public void shouldCacheLifetimeOnTinkerEdge() {
        final TinkerGraph graph = TinkerGraph.open();
        final TinkerVertex v1 = (TinkerVertex) graph.addVertex();
        final TinkerVertex v2 = (TinkerVertex) graph.addVertex();
        final TinkerEdge e = (TinkerEdge) v1.addEdge("knows", v2);

        final Lifetime lifetime = Lifetime.from(1000L, 2000L);
        lifetime.attachTo(e);

        final Lifetime cached = e.getLifetime();
        assertNotNull(cached);
        assertSame(cached, e.getLifetime());
        assertSame(cached, Lifetime.fromProperties(e));
    }

    @Test
    public void shouldInvalidateCacheOnEdgePropertyChangeAndRemoval() {
        final TinkerGraph graph = TinkerGraph.open();
        final TinkerVertex v1 = (TinkerVertex) graph.addVertex();
        final TinkerVertex v2 = (TinkerVertex) graph.addVertex();
        final TinkerEdge e = (TinkerEdge) v1.addEdge("knows", v2);

        final Lifetime lifetime = Lifetime.from(1000L, 2000L);
        lifetime.attachTo(e);

        final Lifetime firstLifetime = e.getLifetime();
        assertSame(firstLifetime, e.getLifetime());

        // Change property
        e.property(Lifetime.TEMPORAL_INTERVALS, "5000:6000");
        final Lifetime secondLifetime = e.getLifetime();
        assertNotSame(firstLifetime, secondLifetime);
        assertEquals(new Date(5000L), secondLifetime.getStartDate());

        // Remove property via TinkerProperty.remove()
        e.property(Lifetime.TEMPORAL_INTERVALS).remove();
        final Lifetime thirdLifetime = e.getLifetime();
        assertNotSame(secondLifetime, thirdLifetime);
        // Falls back to existing startTime/endTime (1000, 2000)
        assertEquals(new Date(1000L), thirdLifetime.getStartDate());
        assertEquals(new Date(2000L), thirdLifetime.getEndDate());

        // Removing remaining temporal properties results in unbounded default
        e.property(Lifetime.START_TIME).remove();
        e.property(Lifetime.END_TIME).remove();
        final Lifetime fourthLifetime = e.getLifetime();
        assertNotSame(thirdLifetime, fourthLifetime);
        assertEquals(Long.MIN_VALUE, fourthLifetime.getStartDate().getTime());
        assertEquals(Long.MAX_VALUE, fourthLifetime.getEndDate().getTime());
    }

    @Test
    public void shouldCacheLifetimeOnTinkerVertexProperty() {
        final TinkerGraph graph = TinkerGraph.open();
        final TinkerVertex v = (TinkerVertex) graph.addVertex();
        final TinkerVertexProperty<String> vp = (TinkerVertexProperty<String>) v.property("city", "Athens");

        final Lifetime lifetime = Lifetime.from(1000L, 2000L);
        lifetime.attachTo(vp);

        assertTrue(vp instanceof TemporalElement);
        final Lifetime cached = vp.getLifetime();
        assertNotNull(cached);
        assertSame(cached, vp.getLifetime());
        assertSame(cached, Lifetime.fromProperties(vp));
        assertEquals(new Date(1000L), cached.getStartDate());
    }

    @Test
    public void shouldInvalidateCacheOnVertexPropertyMetaPropertyRemoval() {
        final TinkerGraph graph = TinkerGraph.open();
        final TinkerVertex v = (TinkerVertex) graph.addVertex();
        final TinkerVertexProperty<String> vp = (TinkerVertexProperty<String>) v.property("city", "Athens");

        final Lifetime lifetime = Lifetime.from(1000L, 2000L);
        lifetime.attachTo(vp);

        final Lifetime firstLifetime = vp.getLifetime();
        assertSame(firstLifetime, vp.getLifetime());

        // Remove meta-property via TinkerProperty.remove()
        vp.property(Lifetime.TEMPORAL_INTERVALS).remove();

        final Lifetime secondLifetime = vp.getLifetime();
        assertNotSame(firstLifetime, secondLifetime);
        // Falls back to startTime/endTime attached by lifetime.attachTo(vp)
        assertEquals(new Date(1000L), secondLifetime.getStartDate());
        assertEquals(new Date(2000L), secondLifetime.getEndDate());

        // When all temporal properties are removed, defaults to unbounded
        vp.property(Lifetime.START_TIME).remove();
        vp.property(Lifetime.END_TIME).remove();
        final Lifetime thirdLifetime = vp.getLifetime();
        assertEquals(Long.MIN_VALUE, thirdLifetime.getStartDate().getTime());
        assertEquals(Long.MAX_VALUE, thirdLifetime.getEndDate().getTime());
    }

    @Test
    public void shouldPreserveCachedLifetimeOnCloneAndCopy() {
        final TinkerGraph graph = TinkerGraph.open();
        final TinkerVertex v1 = (TinkerVertex) graph.addVertex();
        final TinkerVertex v2 = (TinkerVertex) graph.addVertex();
        final TinkerEdge e = (TinkerEdge) v1.addEdge("knows", v2);
        final TinkerVertexProperty<String> vp = (TinkerVertexProperty<String>) v1.property("city", "Athens");

        Lifetime.from(3000L, 4000L).attachTo(e);
        Lifetime.from(5000L, 6000L).attachTo(vp);
        // Attach lifetime to v1 after vp is configured so v1's cache is primed
        Lifetime.from(1000L, 2000L).attachTo(v1);

        // Ensure cache is populated
        final Lifetime v1Lifetime = v1.getLifetime();
        final Lifetime eLifetime = e.getLifetime();
        final Lifetime vpLifetime = vp.getLifetime();

        final TinkerVertex clonedV = (TinkerVertex) v1.clone();
        assertSame(v1Lifetime, clonedV.getLifetime());

        final TinkerEdge clonedE = (TinkerEdge) e.clone();
        assertSame(eLifetime, clonedE.getLifetime());

        final TinkerVertexProperty clonedVp = (TinkerVertexProperty) vp.clone();
        assertSame(vpLifetime, clonedVp.getLifetime());

        final TinkerVertexProperty copiedVp = vp.copy(v2);
        assertSame(vpLifetime, copiedVp.getLifetime());
    }

    @Test
    public void shouldImplementTemporalElementOnDecorators() {
        final TinkerGraph graph = TinkerGraph.open();
        final Vertex v = graph.addVertex("name", "v1");
        final Vertex v2 = graph.addVertex("name", "v2");
        final Edge e = v.addEdge("connects", v2);
        final VertexProperty<String> vp = v.property("status", "active");

        final Lifetime snapLifetime = Lifetime.from(1500L, 1500L);

        final TemporalVertex tv = new TemporalVertex(v, snapLifetime);
        final TemporalEdge te = new TemporalEdge(e, snapLifetime);
        final TemporalVertexProperty<String> tvp = new TemporalVertexProperty<>(vp, snapLifetime);

        assertTrue(tv instanceof TemporalElement);
        assertTrue(te instanceof TemporalElement);
        assertTrue(tvp instanceof TemporalElement);

        assertSame(snapLifetime, tv.getLifetime());
        assertSame(snapLifetime, te.getLifetime());
        assertSame(snapLifetime, tvp.getLifetime());

        assertSame(snapLifetime, Lifetime.fromProperties(tv));
        assertSame(snapLifetime, Lifetime.fromProperties(te));
        assertSame(snapLifetime, Lifetime.fromProperties(tvp));
    }
}
