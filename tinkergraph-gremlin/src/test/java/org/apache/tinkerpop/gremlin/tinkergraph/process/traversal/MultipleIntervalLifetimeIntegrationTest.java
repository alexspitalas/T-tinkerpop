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
    public void shouldSupportMultiIntervalContinuousAndPairwiseContinuousPathsWithWindow() {
        final Vertex a = g.addV("person").property("name", "a").next();
        final Vertex b = g.addV("person").property("name", "b").next();
        final Vertex c = g.addV("person").property("name", "c").next();
        final Vertex dCont = g.addV("person").property("name", "d_cont").next();
        final Vertex dPairwiseOnly = g.addV("person").property("name", "d_pairwise").next();

        // Hop 1 (a->b): [100, 200) and [500, 600)
        g.addE("collab").from(a).to(b).lifetime(100L, 200L).addInterval(500L, 600L).next();
        // Hop 2 (b->c): [150, 250) and [550, 650)
        g.addE("collab").from(b).to(c).lifetime(150L, 250L).addInterval(550L, 650L).next();
        // Hop 3a (c->dCont): [580, 700) overlaps [550, 600) common to both a->b and b->c
        g.addE("collab").from(c).to(dCont).lifetime(580L, 700L).next();
        // Hop 3b (c->dPairwiseOnly): [220, 300) overlaps b->c's [150, 250) but NOT a->b's [100, 200)
        g.addE("collab").from(c).to(dPairwiseOnly).lifetime(220L, 300L).next();

        assertEquals(Arrays.asList("d_cont"),
                g.V(a).continuousPath("collab").continuousPath("collab").continuousPath("collab")
                        .values("name").toList());

        assertEquals(Arrays.asList("d_cont", "d_pairwise"),
                g.V(a).pairwiseContinuousPath(true, "collab")
                        .pairwiseContinuousPath(true, "collab")
                        .pairwiseContinuousPath(true, "collab")
                        .values("name").toList());

        // Restricting to window [0, 400) removes the [500, 700) intervals, so continuousPath is empty
        // while pairwiseContinuousPath still reaches d_pairwise via [100, 200) -> [150, 250) -> [220, 300)
        assertTrue(g.V(a).window(0L, 400L)
                .continuousPath("collab").continuousPath("collab").continuousPath("collab")
                .toList().isEmpty());
        assertEquals(Arrays.asList("d_pairwise"),
                g.V(a).window(0L, 400L)
                        .pairwiseContinuousPath("collab")
                        .pairwiseContinuousPath("collab")
                        .pairwiseContinuousPath("collab")
                        .values("name").toList());
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

    @Test
    public void shouldCascadeVertexDropIntervalToIncidentEdgesAndKeepAdjacentVerticesUnchanged() {
        final Vertex a = g.addV("person").property("name", "a")
                .lifetime("2020-01-01", "2030-01-01").next();
        final Vertex b = g.addV("person").property("name", "b")
                .lifetime("2020-01-01", "2030-01-01").next();

        final Edge outEdge = g.addE("knows").from(a).to(b)
                .property("weight", 1.0)
                .lifetime("2022-01-01", "2028-01-01").next();
        final Edge inEdge = g.addE("follows").from(b).to(a)
                .lifetime("2021-01-01", "2027-01-01").next();
        final Edge selfLoop = g.addE("self").from(a).to(a)
                .lifetime("2023-01-01", "2029-01-01").next();
        final Edge untimedEdge = g.addE("untimed").from(a).to(b).next();

        g.V(a).dropInterval("2024-01-01", "2026-01-01").iterate();

        assertEquals(Arrays.asList(
                        interval("2020-01-01", "2024-01-01"),
                        interval("2026-01-01", "2030-01-01")),
                Lifetime.fromProperties(a).getIntervals());

        // Adjacent vertex b is unaffected
        assertEquals(Arrays.asList(interval("2020-01-01", "2030-01-01")),
                Lifetime.fromProperties(b).getIntervals());

        // Incident timed edges (out, in, self-loop) are trimmed to stay within a's lifetime
        assertEquals(Arrays.asList(
                        interval("2022-01-01", "2024-01-01"),
                        interval("2026-01-01", "2028-01-01")),
                Lifetime.fromProperties(outEdge).getIntervals());
        assertEquals(Double.valueOf(1.0), outEdge.value("weight"));

        assertEquals(Arrays.asList(
                        interval("2021-01-01", "2024-01-01"),
                        interval("2026-01-01", "2027-01-01")),
                Lifetime.fromProperties(inEdge).getIntervals());

        assertEquals(Arrays.asList(
                        interval("2023-01-01", "2024-01-01"),
                        interval("2026-01-01", "2029-01-01")),
                Lifetime.fromProperties(selfLoop).getIntervals());

        // Untimed incident edge and untimed vertex property remain untimed
        assertFalse(Lifetime.hasLifetimeProperties(untimedEdge));
        assertFalse(Lifetime.hasLifetimeProperties(a.property("name")));
    }

    @Test
    public void shouldCascadeVertexDropIntervalToTimedProperties() {
        final Vertex alice = g.addV("person").property("name", "alice")
                .lifetime("2020-01-01", "2030-01-01")
                .lifetimeProperty("title", "Engineer", "2022-01-01", "2028-01-01")
                .next();

        g.V(alice).dropInterval("2024-01-01", "2026-01-01").iterate();

        assertEquals(Arrays.asList(
                        interval("2022-01-01", "2024-01-01"),
                        interval("2026-01-01", "2028-01-01")),
                Lifetime.fromProperties(alice.property("title")).getIntervals());
        assertFalse(Lifetime.hasLifetimeProperties(alice.property("name")));
        assertEquals("alice", alice.value("name"));
    }

    @Test
    public void shouldRejectSoftDropAndRemoveOnHardDropWhenIncidentEdgeOrPropertyBecomesEmpty() {
        final Vertex a = g.addV("person").property("name", "a")
                .lifetime("2020-01-01", "2030-01-01")
                .lifetimeProperty("tempRole", "intern", "2024-06-01", "2024-09-01")
                .next();
        final Vertex b = g.addV("person").property("name", "b")
                .lifetime("2020-01-01", "2030-01-01").next();

        final Edge longEdge = g.addE("knows").from(a).to(b)
                .lifetime("2020-01-01", "2030-01-01").next();
        final Edge shortEdge = g.addE("mentors").from(a).to(b)
                .lifetime("2024-03-01", "2024-11-01").next();

        // SOFT mode rejects atomically when an incident edge would become empty
        assertThrows(IllegalArgumentException.class,
                () -> g.V(a).dropInterval("2024-01-01", "2025-01-01").iterate());

        // Nothing was mutated on SOFT failure
        assertEquals(Arrays.asList(interval("2020-01-01", "2030-01-01")),
                Lifetime.fromProperties(a).getIntervals());
        assertEquals(Arrays.asList(interval("2020-01-01", "2030-01-01")),
                Lifetime.fromProperties(longEdge).getIntervals());
        assertTrue(g.E(shortEdge.id()).hasNext());
        assertTrue(a.property("tempRole").isPresent());

        // HARD mode trims overlapping edges/properties and removes the emptied ones while keeping vertex a alive
        g.V(a).dropInterval("2024-01-01", "2025-01-01", Lifetime.DropMode.HARD).iterate();

        assertTrue(g.V(a.id()).hasNext());
        assertEquals(Arrays.asList(
                        interval("2020-01-01", "2024-01-01"),
                        interval("2025-01-01", "2030-01-01")),
                Lifetime.fromProperties(a).getIntervals());
        assertEquals(Arrays.asList(
                        interval("2020-01-01", "2024-01-01"),
                        interval("2025-01-01", "2030-01-01")),
                Lifetime.fromProperties(longEdge).getIntervals());
        assertFalse(g.E(shortEdge.id()).hasNext());
        assertFalse(a.property("tempRole").isPresent());
        assertEquals("a", a.value("name"));
    }

    @Test
    public void shouldEnforceVertexPropertyContainmentAndCascadeOnVertexReplace() {
        final Vertex a = g.addV("person")
                .lifetime("2020-01-01", "2030-01-01")
                .lifetimeProperty("role", "engineer", "2021-01-01", "2025-01-01")
                .next();
        final Vertex b = g.addV("person")
                .lifetime("2020-01-01", "2030-01-01").next();
        final Edge edge = g.addE("knows").from(a).to(b)
                .lifetime("2020-01-01", "2028-01-01").next();

        // Setting a property lifetime outside parent vertex fails
        assertThrows(IllegalArgumentException.class,
                () -> g.V(a).lifetimeProperty("bad", "val", "2019-01-01", "2025-01-01").iterate());

        // Expanding a VertexProperty outside parent vertex fails; shrinking within parent succeeds
        assertThrows(IllegalArgumentException.class,
                () -> g.V(a).properties("role").addInterval("2029-01-01", "2031-01-01").iterate());
        g.V(a).properties("role").dropInterval("2022-01-01", "2023-01-01").iterate();
        assertEquals(Arrays.asList(
                        interval("2021-01-01", "2022-01-01"),
                        interval("2023-01-01", "2025-01-01")),
                Lifetime.fromProperties(a.property("role")).getIntervals());

        // Replacing vertex lifetime shrinks incident edge and timed property to the new intersection
        g.V(a).lifetime("2022-06-01", "2026-01-01").iterate();
        assertEquals(Arrays.asList(interval("2022-06-01", "2026-01-01")),
                Lifetime.fromProperties(edge).getIntervals());
        assertEquals(Arrays.asList(interval("2023-01-01", "2025-01-01")),
                Lifetime.fromProperties(a.property("role")).getIntervals());

        // Setting or adding an empty lifetime is rejected
        assertThrows(IllegalArgumentException.class,
                () -> g.V(a).lifetime(Lifetime.EMPTY).iterate());
        assertThrows(IllegalArgumentException.class,
                () -> g.V(a).addInterval(Lifetime.EMPTY, null).iterate());
    }

    private static Lifetime.Interval interval(final Object startTime, final Object endTime) {
        final Date start = Lifetime.toStartDate(startTime);
        final Date end = Lifetime.toEndDate(endTime);
        return new Lifetime.Interval(start, end);
    }
}
