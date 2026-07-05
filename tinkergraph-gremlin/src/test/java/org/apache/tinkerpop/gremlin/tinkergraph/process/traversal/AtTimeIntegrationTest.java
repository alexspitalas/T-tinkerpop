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

import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AtTimeStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalEdge;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalVertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.atTime;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for the {@code atTime()} temporal snapshot step.
 *
 * <p>Tests are grouped into two concerns:</p>
 * <ol>
 *   <li><b>Correctness</b>: verifies that the right elements are included or excluded
 *       at various snapshot instants across OLTP traversal patterns.</li>
 *   <li><b>Efficiency</b>: verifies that the element-wrapping approach does NOT inject
 *       extra filter steps into the traversal plan — the step list must stay lean.</li>
 * </ol>
 */
public class AtTimeIntegrationTest {

    // Snapshot dates used across tests
    private static final String BEFORE_ALL  = "2019-01-01";  // before every element
    private static final String T_2021      = "2021-06-01";  // alice alive, bob dead, knows dead, likes dead
    private static final String T_2024      = "2024-06-01";  // alice alive, bob alive, knows alive, likes dead
    private static final String T_2028      = "2028-06-01";  // alice alive, bob alive, knows dead, likes alive
    private static final String T_2032      = "2032-06-01";  // alice dead, bob alive, knows dead, likes dead
    private static final String AFTER_ALL   = "2040-01-01";  // after every element

    private TinkerGraph graph;
    private GraphTraversalSource g;

    /**
     * Graph structure:
     *
     * <pre>
     *  alice (2020–2030)  --[knows, 2022–2025]--> bob (2022–2035)
     *                     --[likes, 2026–2030]--> bob
     *
     *  carol (no lifetime)  <-- open-world, always alive
     * </pre>
     *
     * Temporal properties on alice:
     *  - title="analyst"   [2020–2022]
     *  - title="architect" [2024–2026]
     */
    @Before
    public void setUp() {
        graph = TinkerGraph.open();
        g = graph.traversal();

        // Vertices
        g.addV("person").property("name", "alice")
                .lifetime("2020-01-01", "2030-12-31")
                .lifetimeProperty("past_title", "analyst",   "2020-01-01", "2022-12-31")
                .lifetimeProperty("current_title", "architect", "2024-01-01", "2026-12-31")
                .next();

        g.addV("person").property("name", "bob")
                .lifetime("2022-01-01", "2035-12-31")
                .next();

        g.addV("person").property("name", "carol")
                // no lifetime: open-world, always visible
                .next();

        // Edges
        Vertex alice = g.V().hasLabel("person").has("name", "alice").next();
        Vertex bob   = g.V().hasLabel("person").has("name", "bob").next();
        Vertex carol = g.V().hasLabel("person").has("name", "carol").next();

        // Edge: alice -[knows]-> bob, alive 2022–2025
        g.addE("knows").from(alice).to(bob)
                .lifetime("2022-01-01", "2025-12-31")
                .next();

        // Edge: alice -[likes]-> bob, alive 2026–2030
        g.addE("likes").from(alice).to(bob)
                .lifetime("2026-01-01", "2030-12-31")
                .next();
    }

    @After
    public void tearDown() {
        graph.close();
    }

    // ════════════════════════════════════════════════════════════════════════
    // CORRECTNESS: Vertex filtering
    // ════════════════════════════════════════════════════════════════════════

    @Test
    public void shouldReturnNoVerticesBeforeAnyoneWasBorn() {
        // Open-world vertices (carol) are always visible
        final List<Vertex> result = g.V().atTime(BEFORE_ALL).toList();
        // Only carol (no lifetime) survives; alice and bob not yet born
        assertThat(result, hasSize(1));
    }

    @Test
    public void shouldReturnOnlyAliveVerticesAt2021() {
        // alice: [2020–2030] alive ✓   bob: [2022–2035] not born ✗   carol: no lifetime ✓
        final List<Vertex> result = g.V().atTime(T_2021).toList();
        assertThat(result, hasSize(2));  // alice + carol
    }

    @Test
    public void shouldReturnAliceAndBobAt2024() {
        // alice: alive ✓   bob: alive ✓   carol: always ✓
        final List<Vertex> result = g.V().atTime(T_2024).toList();
        assertThat(result, hasSize(3));
    }

    @Test
    public void shouldReturnOnlyBobAndCarolAt2032() {
        // alice: ended 2030 ✗   bob: alive ✓   carol: always ✓
        final List<Vertex> result = g.V().atTime(T_2032).toList();
        assertThat(result, hasSize(2));
    }

    @Test
    public void shouldReturnOnlyCarolAfterAll() {
        // Everyone with a lifetime is dead
        final List<Vertex> result = g.V().atTime(AFTER_ALL).toList();
        assertThat(result, hasSize(1));
    }

    @Test
    public void shouldAcceptDateObject() {
        final Date date = Date.from(Instant.parse("2024-06-01T00:00:00Z"));
        final List<Vertex> result = g.V().atTime(date).toList();
        assertThat(result, hasSize(3));  // alice + bob + carol
    }

    @Test
    public void shouldAcceptInstantObject() {
        final Instant instant = Instant.parse("2021-06-01T00:00:00Z");
        final List<Vertex> result = g.V().atTime(instant).toList();
        assertThat(result, hasSize(2));  // alice + carol
    }

    @Test
    public void shouldAcceptEpochMillisLong() {
        // 2024-06-01 epoch millis
        final long epochMillis = Instant.parse("2024-06-01T00:00:00Z").toEpochMilli();
        final List<Vertex> result = g.V().atTime(epochMillis).toList();
        assertThat(result, hasSize(3));
    }

    // ════════════════════════════════════════════════════════════════════════
    // CORRECTNESS: Element wrapping propagates through navigation
    // ════════════════════════════════════════════════════════════════════════

    @Test
    public void passedVerticesShouldBeWrappedInTemporalVertex() {
        final List<Vertex> result = g.V().atTime(T_2024).toList();
        assertTrue("All passing vertices should be TemporalVertex instances",
                result.stream().allMatch(v -> v instanceof TemporalVertex));
    }

    @Test
    public void shouldFilterEdgesWhenWalkingOut() {
        // At T_2021: alice is alive; edge[knows, 2022–2025] is NOT alive; bob[born 2022] is NOT alive
        // So alice.out("knows") should return empty at 2021
        final List<Vertex> result = g.V().atTime(T_2021).out("knows").toList();
        assertThat("Edge not born yet — should be empty", result, empty());
    }

    @Test
    public void shouldReachBobWhenBothEdgeAndBobAreAlive() {
        // At T_2024: alice alive, knows-edge alive, bob alive -> should return bob
        final List<Vertex> result = g.V().atTime(T_2024).out("knows").toList();
        assertThat(result, hasSize(1));
        assertThat(result.get(0), instanceOf(TemporalVertex.class));
    }

    @Test
    public void shouldFilterDeadEdgeWhenWalkingOutE() {
        // At T_2032: likes-edge [2026–2030] dead ✗, knows-edge [2022–2025] dead ✗
        // alice is dead at 2032, so alice's outEdges aren't accessible (alice filtered)
        // Only vertices alive at 2032: bob, carol (neither has outEdges)
        final List<Edge> edges = g.V().atTime(T_2032).outE().toList();
        assertThat("Alice dead at 2032; no outgoing edges from alive vertices", edges, empty());
    }

    @Test
    public void shouldReturnTemporalEdgesFromOutE() {
        final List<Edge> edges = g.V().atTime(T_2024).outE("knows").toList();
        assertThat(edges, hasSize(1));
        assertThat(edges.get(0), instanceOf(TemporalEdge.class));
    }

    @Test
    public void shouldPropagateTemporalContextThroughOutEInV() {
        // alice.outE("knows").inV() at 2024 should give bob as TemporalVertex
        final List<Vertex> result = g.V().atTime(T_2024).outE("knows").inV().toList();
        assertThat(result, hasSize(1));
        assertThat(result.get(0), instanceOf(TemporalVertex.class));
    }

    // ════════════════════════════════════════════════════════════════════════
    // CORRECTNESS: Temporal property filtering
    // ════════════════════════════════════════════════════════════════════════

    @Test
    public void shouldReturnOnlyAlivePropertyValue() {
        // At T_2021: alice's "past_title" property:
        //   analyst[2020–2022] alive ✓
        final List<Object> values = g.V().atTime(T_2021).values("past_title").toList();
        assertThat(values, contains("analyst"));
    }

    @Test
    public void shouldReturnCurrentPropertyValueAt2024() {
        // At T_2024: current_title architect[2024–2026] alive ✓
        final List<Object> values = g.V().atTime(T_2024).values("current_title").toList();
        assertThat(values, contains("architect"));
    }

    @Test
    public void shouldHasFilterWorkWithTemporalProperty() {
        // At T_2021: past_title is alive, current_title is not
        final List<Vertex> analyst   = g.V().atTime(T_2021).has("past_title", "analyst").toList();
        final List<Vertex> architect = g.V().atTime(T_2021).has("current_title", "architect").toList();

        assertThat(analyst,   hasSize(1));
        assertThat(architect, empty());
    }

    @Test
    public void shouldHasFilterWorkWithCurrentTitle() {
        // At T_2024: architect alive; has("current_title","architect") -> alice
        final List<Vertex> result = g.V().atTime(T_2024).has("current_title", "architect").toList();
        assertThat(result, hasSize(1));
    }

    @Test
    public void shouldReturnTemporalVertexPropertyFromPropertiesStep() {
        final List<? extends org.apache.tinkerpop.gremlin.structure.Property<Object>> props = g.V().atTime(T_2021).properties("past_title").toList();
        assertThat(props, hasSize(1));
        assertThat(props.get(0),
                instanceOf(org.apache.tinkerpop.gremlin.structure.temporal.TemporalVertexProperty.class));
    }

    // ════════════════════════════════════════════════════════════════════════
    // CORRECTNESS: Nested traversals (where, repeat)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    public void shouldPropagateContextThroughWhereTraversal() {
        // At T_2021: alice is alive but she can't reach bob (knows edge dead)
        final List<Vertex> result = g.V().atTime(T_2021).where(atTime(T_2021).out("knows")).toList();
        assertThat(result, empty());
    }

    @Test
    public void shouldPropagateContextThroughWhereAt2024() {
        // At T_2024: alice can reach bob via knows edge
        final List<Vertex> result = g.V().atTime(T_2024).where(atTime(T_2024).out("knows")).toList();
        assertThat(result, hasSize(1));
    }

    @Test
    public void shouldFilterWithinRepeat() {
        // repeat(atTime(d).out()) — temporal filter inside repeat loop
        // At T_2024, alice -> bob in 1 hop; can't go further (bob has no out edges in snapshot)
        final List<Vertex> result = g.V().atTime(T_2024)
                .repeat(atTime(T_2024).out())
                .times(1)
                .toList();
        assertThat(result, hasSize(1));  // bob
    }

    // ════════════════════════════════════════════════════════════════════════
    // EFFICIENCY: Step list must stay lean (no injected filter steps)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    public void stepListShouldNotExpandForBasicAtTime() {
        // g.V().atTime(d)  →  [GraphStep, AtTimeStep]  — exactly 2 steps
        final GraphTraversal<Vertex, Vertex> t = g.V().atTime(T_2024);
        t.asAdmin().applyStrategies();

        final List<Step> steps = t.asAdmin().getSteps();
        assertEquals("g.V().atTime(d) must produce exactly 2 steps", 2, steps.size());
        assertThat(steps.get(0), instanceOf(GraphStep.class));
        assertThat(steps.get(1), instanceOf(AtTimeStep.class));
    }

    @Test
    public void stepListShouldNotExpandForAtTimeOut() {
        // g.V().atTime(d).out()  →  [GraphStep, AtTimeStep, VertexStep]  — exactly 3 steps
        final GraphTraversal<Vertex, Vertex> t = g.V().atTime(T_2024).out("knows");
        t.asAdmin().applyStrategies();

        final List<Step> steps = t.asAdmin().getSteps();
        assertEquals(
            "Element-wrapping approach: g.V().atTime(d).out() must produce exactly 3 steps — no injected filters",
            3, steps.size());
        assertThat(steps.get(0), instanceOf(GraphStep.class));
        assertThat(steps.get(1), instanceOf(AtTimeStep.class));
        assertThat(steps.get(2), instanceOf(VertexStep.class));
    }

    @Test
    public void stepListShouldNotExpandForAtTimeOutEInV() {
        // g.V().atTime(d).outE().inV()  →  IncidentToAdjacentStrategy optimizes to out()
        // [GraphStep, AtTimeStep, VertexStep] — 3 steps
        final GraphTraversal<?, Vertex> t = g.V().atTime(T_2024).outE("knows").inV();
        t.asAdmin().applyStrategies();

        final List<Step> steps = t.asAdmin().getSteps();
        assertEquals(
            "Element-wrapping approach: outE().inV() must produce exactly 3 steps (after optimization) — no injected filters",
            3, steps.size());
    }
}
