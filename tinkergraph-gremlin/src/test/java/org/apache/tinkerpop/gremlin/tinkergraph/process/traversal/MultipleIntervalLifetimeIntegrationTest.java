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
package org.apache.tinkerpop.gremlin.tinkergraph.process.traversal;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;

public class MultipleIntervalLifetimeIntegrationTest {

    private TinkerGraph graph;
    private GraphTraversalSource g;

    @Before
    public void setUp() {
        graph = TinkerGraph.open();
        g = graph.traversal();
    }

    @After
    public void tearDown() {
        graph.close();
    }

    @Test
    public void shouldAddIntervalToVertex() {
        final Vertex marko = g.addV("person").property("name", "marko")
                .lifetime("2020-01-01", "2020-12-31").next();

        g.V().has("name", "marko").addInterval("2024-01-01", "2024-12-31").iterate();

        assertEquals(Arrays.asList(
                        interval("2020-01-01", "2020-12-31"),
                        interval("2024-01-01", "2024-12-31")),
                Lifetime.fromProperties(marko).getIntervals());
        assertFalse(g.V(marko).atTime("2022-01-01").hasNext());
        assertTrue(g.V(marko).atTime("2024-06-01").hasNext());
    }

    @Test
    public void shouldRejectAddedEdgeIntervalOutsideVertexLifetimes() {
        final Vertex alice = g.addV("person").property("name", "alice")
                .lifetime("2020-01-01", "2030-12-31").next();
        final Vertex bob = g.addV("person").property("name", "bob")
                .lifetime("2020-01-01", "2030-12-31").next();
        final Edge edge = g.addE("knows").from(alice).to(bob)
                .lifetime("2022-01-01", "2023-12-31").next();

        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> g.E(edge).addInterval("2031-01-01", "2031-12-31").iterate());

        assertTrue(exception.getMessage().contains("Cannot create edge with lifetime"));
        assertEquals(Lifetime.from("2022-01-01", "2023-12-31"), Lifetime.fromProperties(edge));
    }

    @Test
    public void shouldDropIntervalAndUpdateSnapshotVisibility() {
        final Vertex marko = g.addV("person").property("name", "marko")
                .lifetime("2020-01-01", "2030-12-31").next();

        g.V(marko).dropInterval("2024-01-01", "2024-12-31").iterate();

        assertTrue(g.V(marko).atTime("2023-06-01").hasNext());
        assertFalse(g.V(marko).atTime("2024-06-01").hasNext());
        assertTrue(g.V(marko).atTime("2025-06-01").hasNext());
    }

    @Test
    public void shouldRejectSoftDropOfLastInterval() {
        final Vertex exact = g.addV("person").lifetime("2020-01-01", "2020-12-31").next();
        assertThrows(IllegalArgumentException.class,
                () -> g.V(exact).dropInterval("2020-01-01", "2020-12-31").iterate());
        assertEquals(Lifetime.from("2020-01-01", "2020-12-31"), Lifetime.fromProperties(exact));

        final Vertex covered = g.addV("person").lifetime("2020-01-01", "2020-12-31").next();
        g.V(covered).addInterval("2022-01-01", "2022-12-31").iterate();
        assertThrows(IllegalArgumentException.class,
                () -> g.V(covered).dropInterval("2019-01-01", "2023-01-01").iterate());
        assertEquals(2, Lifetime.fromProperties(covered).getIntervals().size());
    }

    @Test
    public void shouldRemoveEdgeAndIncidentEdgesOnHardDrop() {
        final Vertex source = g.addV("person").lifetime("2020-01-01", "2030-12-31").next();
        final Vertex target = g.addV("person").lifetime("2020-01-01", "2030-12-31").next();
        final Edge edge = g.addE("knows").from(source).to(target)
                .lifetime("2022-01-01", "2023-12-31").next();
        final Object edgeId = edge.id();
        assertSame(edge, g.E(edge).dropInterval("2022-01-01", "2023-12-31", Lifetime.DropMode.HARD).next());
        assertFalse(g.E(edgeId).hasNext());

        final Edge incident = g.addE("knows").from(source).to(target)
                .lifetime("2022-01-01", "2023-12-31").next();
        final Object sourceId = source.id();
        final Object incidentId = incident.id();
        assertSame(source, g.V(source).dropInterval("2019-01-01", "2031-01-01", Lifetime.DropMode.HARD).next());
        assertFalse(g.V(sourceId).hasNext());
        assertFalse(g.E(incidentId).hasNext());
    }

    @Test
    public void shouldLeaveMissedAndInitiallyUnboundedDropsUnchanged() {
        final Vertex bounded = g.addV("person").lifetime("2020-01-01", "2020-12-31").next();
        g.V(bounded).dropInterval("2022-01-01", "2022-12-31", Lifetime.DropMode.HARD).iterate();
        assertEquals(Lifetime.from("2020-01-01", "2020-12-31"), Lifetime.fromProperties(bounded));

        final Vertex unbounded = g.addV("person").next();
        g.V(unbounded).dropInterval("2020-01-01", "2020-12-31", Lifetime.DropMode.HARD).iterate();
        assertTrue(g.V(unbounded).hasNext());
        assertEquals(2, Lifetime.fromProperties(unbounded).getIntervals().size());
    }

    private static Lifetime.Interval interval(final Object startTime, final Object endTime) {
        final Date start = Lifetime.toStartDate(startTime);
        final Date end = Lifetime.toEndDate(endTime);
        return new Lifetime.Interval(start, end);
    }
}
