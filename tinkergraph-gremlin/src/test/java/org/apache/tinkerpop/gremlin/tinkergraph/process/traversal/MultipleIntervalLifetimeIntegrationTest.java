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

        assertTrue(exception.getMessage().contains("Cannot add an interval to edge lifetime"));
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
    public void shouldLeaveMissedDropUnchangedAndSplitUnboundedLifetime() {
        final Vertex bounded = g.addV("person").lifetime("2020-01-01", "2020-12-31").next();
        g.V(bounded).dropInterval("2022-01-01", "2022-12-31", Lifetime.DropMode.HARD).iterate();
        assertEquals(Lifetime.from("2020-01-01", "2020-12-31"), Lifetime.fromProperties(bounded));

        final Vertex unbounded = g.addV("person").next();
        g.V(unbounded).dropInterval("2020-01-01", "2020-12-31", Lifetime.DropMode.HARD).iterate();
        assertTrue(g.V(unbounded).hasNext());
        assertEquals(2, Lifetime.fromProperties(unbounded).getIntervals().size());
    }

    @Test
    public void shouldSupportMultiIntervalSequentialPathAndWindowedPathFiltering() {
        final Vertex a = g.addV("person").property("name", "a").next();
        final Vertex b = g.addV("person").property("name", "b").next();
        final Vertex c = g.addV("person").property("name", "c").next();

        // Edge a->b has an early interval [2020, 2021) and a late interval [2028, 2029)
        g.addE("knows").from(a).to(b)
                .lifetime("2020-01-01", "2021-01-01")
                .addInterval("2028-01-01", "2029-01-01")
                .next();
        // Edge b->c is active [2023, 2024), which is after a->b's first interval [2020, 2021)
        g.addE("knows").from(b).to(c)
                .lifetime("2023-01-01", "2024-01-01")
                .next();

        assertEquals(Arrays.asList("c"),
                g.V(a).sequentialPath("knows").sequentialPath("knows").values("name").toList());

        // Restricting to window [2022, 2030) excludes a->b's [2020, 2021) interval, leaving only [2028, 2029),
        // which cannot precede b->c [2023, 2024)
        assertTrue(g.V(a).window("2022-01-01", "2030-01-01")
                .sequentialPath("knows").sequentialPath("knows").toList().isEmpty());
    }

    @Test
    public void shouldSupportMultiIntervalAndWindowedTemporalPageRank() {
        final Vertex a = g.addV("person").property("name", "a").next();
        final Vertex b = g.addV("person").property("name", "b").next();
        final Vertex c = g.addV("person").property("name", "c").next();

        // a->b active in [2020, 2021) and [2025, 2026)
        g.addE("knows").from(a).to(b)
                .lifetime("2020-01-01", "2021-01-01")
                .addInterval("2025-01-01", "2026-01-01")
                .next();
        // b->c active in [2022, 2023)
        g.addE("knows").from(b).to(c)
                .lifetime("2022-01-01", "2023-01-01")
                .next();

        // In window [2024, 2027), only a->b [2025, 2026) is active; b->c is outside the window
        g.V().window("2024-01-01", "2027-01-01")
                .temporalPageRank()
                .with(org.apache.tinkerpop.gremlin.process.traversal.step.map.TemporalPageRank.propertyName, "winRank")
                .iterate();

        assertTrue(g.V(b).<Double>values("winRank").next() > 0.0d);
        assertEquals(0.0d, g.V(c).<Double>values("winRank").next(), 1e-9);
    }

    @Test
    public void shouldSupportWindowedMultiIntervalTemporalShortestPathOnGraphComputer() {
        final Vertex a = g.addV("person").property("name", "a").next();
        final Vertex b = g.addV("person").property("name", "b").next();

        // Edge a->b has two intervals: [100, 200) and [500, 600)
        g.addE("knows").from(a).to(b)
                .lifetime(100L, 200L)
                .addInterval(500L, 600L)
                .next();

        // Without window, starting at t=250 reaches b via [500, 600) (paths: [a] and [a, b])
        assertEquals(2, g.withComputer().V(a)
                .temporalShortestPath(
                        org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget.EARLIEST_ARRIVAL,
                        250L)
                .toList().size());

        // Window [0, 300) restricts a->b to [100, 200); starting at t=250 cannot use [500, 600) (only [a] returned)
        assertEquals(1, g.withComputer().V(a).window(0L, 300L)
                .temporalShortestPath(
                        org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget.EARLIEST_ARRIVAL,
                        250L)
                .toList().size());

        // Starting at t=50 within window [0, 300) reaches b via [100, 200)
        assertEquals(2, g.withComputer().V(a).window(0L, 300L)
                .temporalShortestPath(
                        org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget.EARLIEST_ARRIVAL,
                        50L)
                .toList().size());
    }

    private static Lifetime.Interval interval(final Object startTime, final Object endTime) {
        final Date start = Lifetime.toStartDate(startTime);
        final Date end = Lifetime.toEndDate(endTime);
        return new Lifetime.Interval(start, end);
    }
}
