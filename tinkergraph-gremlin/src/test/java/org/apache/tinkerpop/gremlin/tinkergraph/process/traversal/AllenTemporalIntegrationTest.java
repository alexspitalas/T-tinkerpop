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
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.Date;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

public class AllenTemporalIntegrationTest {

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
    public void shouldNormalizeStringTemporalPropertiesForAllenRelations() {
        final Vertex reference = g.addV("interval").
                property("name", "reference").
                property(Lifetime.START_TIME, date("2024-01-01T00:00:00Z")).
                property(Lifetime.END_TIME, date("2028-01-01T00:00:00Z")).
                next();

        g.addV("interval").
                property("name", "string-overlap").
                property(Lifetime.START_TIME, "2020-01-01T00:00:00Z").
                property(Lifetime.END_TIME, "2025-01-01T00:00:00Z").
                next();
        g.addV("interval").
                property("name", "string-before").
                property(Lifetime.START_TIME, "2018-01-01T00:00:00Z").
                property(Lifetime.END_TIME, "2020-01-01T00:00:00Z").
                next();

        assertThat(g.V().hasLabel("interval").temporalOverlaps(reference).values("name").toList(),
                contains("string-overlap"));
    }

    @Test
    public void shouldTreatMissingEndTimeAsOpenEndedForAllenRelations() {
        final Vertex reference = g.addV("interval").
                property("name", "reference").
                property(Lifetime.START_TIME, date("2020-01-01T00:00:00Z")).
                property(Lifetime.END_TIME, date("2030-01-01T00:00:00Z")).
                next();

        g.addV("interval").
                property("name", "open-ended").
                property(Lifetime.START_TIME, "2020-01-01T00:00:00Z").
                next();
        g.addV("interval").
                property("name", "same-end").
                property(Lifetime.START_TIME, "2020-01-01T00:00:00Z").
                property(Lifetime.END_TIME, "2030-01-01T00:00:00Z").
                next();

        assertThat(g.V().hasLabel("interval").temporalStartedBy(reference).values("name").toList(),
                contains("open-ended"));
    }

    @Test
    public void shouldTreatReferenceWithoutTemporalPropertiesAsAllTimeForAllenRelations() {
        final Vertex reference = g.addV("interval").
                property("name", "all-time-reference").
                next();

        g.addV("interval").
                property("name", "bounded-current").
                property(Lifetime.START_TIME, date("2024-01-01T00:00:00Z")).
                property(Lifetime.END_TIME, date("2025-01-01T00:00:00Z")).
                next();

        assertThat(g.V().hasLabel("interval").temporalDuring(reference).values("name").toList(),
                contains("bounded-current"));
    }

    @Test
    public void shouldTreatCurrentWithoutTemporalPropertiesAsAllTimeForAllenRelations() {
        final Vertex reference = g.addV("interval").
                property("name", "bounded-reference").
                property(Lifetime.START_TIME, date("2024-01-01T00:00:00Z")).
                property(Lifetime.END_TIME, date("2025-01-01T00:00:00Z")).
                next();

        g.addV("interval").
                property("name", "all-time-current").
                next();

        assertThat(g.V().hasLabel("interval").temporalContains(reference).values("name").toList(),
                contains("all-time-current"));
    }

    @Test
    public void shouldTreatElementsWithoutTemporalPropertiesAsEqualAllTimeIntervals() {
        final Vertex reference = g.addV("interval").
                property("name", "all-time-reference").
                next();

        g.addV("interval").
                property("name", "all-time-current").
                next();

        assertThat(g.V().has("name", "all-time-current").temporalEquals(reference).values("name").toList(),
                contains("all-time-current"));
    }

    @Test
    public void shouldEvaluateAllenStepsAndTemporalPForIntervalAndMultiIntervalCombinations() {
        final Vertex o1 = g.addV("process").property("name", "o1")
                .lifetime("2020-01-01T00:00:00Z", "2022-01-01T00:00:00Z")
                .addInterval("2024-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
                .next();

        final Lifetime.Interval i1 = new Lifetime.Interval(
                date("2020-06-01T00:00:00Z"),
                date("2021-06-01T00:00:00Z"));
        final Lifetime.Interval i2 = new Lifetime.Interval(
                date("2021-06-01T00:00:00Z"),
                date("2023-01-01T00:00:00Z"));

        // 1. i1 x i2 in a traversal (Interval stream filtered by Interval reference & TemporalP)
        assertThat(g.inject(i1).temporalMeets(i2).toList(), contains(i1));
        assertThat(g.inject(i1).is(org.apache.tinkerpop.gremlin.process.traversal.TemporalP.meets(i2)).toList(), contains(i1));

        // 2. i1 x o1 (Interval stream filtered by Multi-Interval Vertex o1)
        assertThat(g.inject(i1).temporalDuring(o1).toList(), contains(i1));
        assertThat(g.inject(i1).is(org.apache.tinkerpop.gremlin.process.traversal.TemporalP.during(o1)).toList(), contains(i1));

        // 3. o1 x i1 (Multi-Interval Vertex o1 filtered by single Interval i1)
        assertThat(g.V(o1).temporalContains(i1).values("name").toList(), contains("o1"));
        assertThat(g.V(o1).filter(org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.is(
                org.apache.tinkerpop.gremlin.process.traversal.TemporalP.contains(i1))).values("name").toList(), contains("o1"));
    }

    @Test
    public void shouldSupportLigozatGeneralizedRelationsAndIndexAccelerationForAllConvexOperators() {
        graph.createIndex(Lifetime.TEMPORAL_INTERVALS, Vertex.class);

        final Vertex vEarly = g.addV("task").property("name", "early")
                .lifetime(100L, 200L).next();
        final Vertex vAbutting = g.addV("task").property("name", "abutting")
                .lifetime(200L, 300L).next();
        final Vertex vLate = g.addV("task").property("name", "late")
                .lifetime(400L, 500L).next();
        final Vertex vMulti = g.addV("task").property("name", "multi")
                .lifetime(100L, 200L).addInterval(400L, 500L).next();

        final Lifetime.Interval ref = new Lifetime.Interval(new Date(200L), new Date(300L));

        // Index-backed queries for previously unsupported disjoint relations (precedes, meets, succeeds, metBy)
        assertThat(g.V().has(Lifetime.TEMPORAL_INTERVALS,
                org.apache.tinkerpop.gremlin.process.traversal.TemporalP.meets(ref)).values("name").toList(),
                contains("early"));
        assertThat(g.V().has(Lifetime.TEMPORAL_INTERVALS,
                org.apache.tinkerpop.gremlin.process.traversal.TemporalP.precedes(new Lifetime.Interval(new Date(250L), new Date(300L)))).values("name").toList(),
                contains("early"));
        assertThat(g.V().has(Lifetime.TEMPORAL_INTERVALS,
                org.apache.tinkerpop.gremlin.process.traversal.TemporalP.succeeds(ref)).values("name").toList(),
                contains("late"));
        assertThat(g.V().has(Lifetime.TEMPORAL_INTERVALS,
                org.apache.tinkerpop.gremlin.process.traversal.TemporalP.metBy(new Lifetime.Interval(new Date(50L), new Date(100L)))).values("name").toList(),
                org.hamcrest.Matchers.containsInAnyOrder("early", "multi"));

        // Convex lattice interval [precedes, meets] = [(0,0), (0,1)] via TemporalP.between
        assertThat(g.V().hasLabel("task").filter(org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.is(
                org.apache.tinkerpop.gremlin.process.traversal.TemporalP.between(
                        org.apache.tinkerpop.gremlin.process.traversal.Temporal.precedes,
                        org.apache.tinkerpop.gremlin.process.traversal.Temporal.meets,
                        ref))).values("name").toList(),
                contains("early"));

        // Gap-aware disjoint step: vAbutting [200, 300) sits right inside vMulti's gap [200, 400)!
        assertThat(g.V(vAbutting).temporalDisjoint(vMulti).values("name").toList(),
                contains("abutting"));

        // Exact Ligozat \Pi(2, 4) relation query: vAbutting = (200, 300) relative to vMulti = (100, 200, 400, 500)
        // 200 is zone 3 (y2 = 200), 300 is zone 4 (in the gap (200, 400)) -> Pi(2, 4)[3, 4]
        final org.apache.tinkerpop.gremlin.structure.temporal.GeneralizedRelation gapAbutRelation =
                new org.apache.tinkerpop.gremlin.structure.temporal.GeneralizedRelation(2, 4, 3, 4);
        assertThat(g.V().hasLabel("task").filter(org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.is(
                org.apache.tinkerpop.gremlin.process.traversal.TemporalP.generalized(vMulti, gapAbutRelation))).values("name").toList(),
                contains("abutting"));

        // Pattern matching with where(startKey, TemporalP.generalized(endKey, relation))
        assertThat(g.V(vAbutting).as("a").V(vMulti).as("m")
                .where("a", org.apache.tinkerpop.gremlin.process.traversal.TemporalP.generalized("m", gapAbutRelation))
                .select("a").values("name").toList(),
                contains("abutting"));

        // Step modulation with .with(Temporal.ANY) and TemporalP.any()
        // vMulti = {[100, 200), [400, 500)} vs vAbutting = [200, 300):
        // Under ALL (default), vMulti does not precede or meet vAbutting (since its second interval is [400, 500)).
        // Under ANY, vMulti's first interval [100, 200) meets vAbutting [200, 300)!
        assertThat(g.V(vMulti).temporalMeets(vAbutting).toList().size(), org.hamcrest.Matchers.is(0));
        assertThat(g.V(vMulti).temporalMeets(vAbutting)
                .with(org.apache.tinkerpop.gremlin.process.traversal.Temporal.ANY).values("name").toList(),
                contains("multi"));
        assertThat(g.V().has(Lifetime.TEMPORAL_INTERVALS,
                org.apache.tinkerpop.gremlin.process.traversal.TemporalP.meets(vAbutting).any()).values("name").toList(),
                org.hamcrest.Matchers.containsInAnyOrder("early", "multi"));
    }

    @Test
    public void shouldRememberReachableSubIntervalsAcrossMultiHopSequentialAndPairwisePaths() {
        final Vertex a = g.addV("station").property("name", "A").next();
        final Vertex b = g.addV("station").property("name", "B").next();
        final Vertex c = g.addV("station").property("name", "C").next();
        final Vertex dInvalid = g.addV("station").property("name", "D_invalid").next();
        final Vertex dValid = g.addV("station").property("name", "D_valid").next();

        // Hop 1 (A -> B): active [100, 200)
        g.addE("leg").from(a).to(b).lifetime(100L, 200L).next();
        // Hop 2 (B -> C): has an early interval [50, 80) BEFORE Hop 1, and a valid later interval [250, 300) AFTER Hop 1
        g.addE("leg").from(b).to(c).lifetime(50L, 80L).addInterval(250L, 300L).next();
        // Hop 3 invalid (C -> D_invalid): [120, 150) is after [50, 80) of Hop 2, but BEFORE the actually reached [250, 300) of Hop 2!
        g.addE("leg").from(c).to(dInvalid).lifetime(120L, 150L).next();
        // Hop 3 valid (C -> D_valid): [320, 350) is after the actually reached [250, 300) of Hop 2
        g.addE("leg").from(c).to(dValid).lifetime(320L, 350L).next();

        assertThat(g.V(a).sequentialPath("leg")
                .sequentialPath("leg")
                .sequentialPath("leg").values("name").toList(),
                contains("D_valid"));

        // Same check for non-monotone pairwiseContinuousPath across 3 hops when Hop 2 has two disconnected intervals
        final Vertex p1 = g.addV("node").property("name", "P1").next();
        final Vertex p2 = g.addV("node").property("name", "P2").next();
        final Vertex p3 = g.addV("node").property("name", "P3").next();
        final Vertex p4Disconnected = g.addV("node").property("name", "P4_disconnected").next();
        final Vertex p4Connected = g.addV("node").property("name", "P4_connected").next();
        g.addE("contact").from(p1).to(p2).lifetime(100L, 200L).next();
        g.addE("contact").from(p2).to(p3).lifetime(150L, 250L).addInterval(1000L, 1100L).next();
        g.addE("contact").from(p3).to(p4Disconnected).lifetime(1050L, 1150L).next();
        g.addE("contact").from(p3).to(p4Connected).lifetime(220L, 280L).next();

        assertThat(g.V(p1).pairwiseContinuousPath("contact")
                .pairwiseContinuousPath("contact")
                .pairwiseContinuousPath("contact").values("name").toList(),
                contains("P4_connected"));
    }

    private static Date date(final String instant) {
        return Date.from(Instant.parse(instant));
    }
}
