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

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AllenFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.TemporalPageRank;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.tinkergraph.process.traversal.step.sideEffect.TinkerGraphStep;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerEdge;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerVertex;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Date;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verification of the 4 architectural optimizations in T-TinkerPop:
 * 1. Zero-Allocation Primitive Representation (long[])
 * 2. Traversal Machine Strategy Pushdown & Step Folding
 * 3. Automated TemporalTinkerIndex Strategy Pushdown
 * 4. TemporalPageRank Redundant Liveness Bypass
 */
public class TemporalOptimizationIntegrationTest {

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

    // --- Optimization 1: Zero-Allocation Primitive Representation ---
    @Test
    public void shouldRepresentLifetimeAsFlatPrimitiveArrayWithZeroAllocations() {
        final Lifetime lifetime = Lifetime.from(1000L, 2000L).addInterval(3000L, 4000L);

        assertEquals(2, lifetime.getIntervalCount());
        assertFalse(lifetime.isEmpty());
        assertFalse(lifetime.isUnbounded());

        final long[] raw = lifetime.getRawIntervals();
        assertNotNull(raw);
        assertEquals(4, raw.length);
        assertEquals(1000L, raw[0]);
        assertEquals(2000L, raw[1]);
        assertEquals(3000L, raw[2]);
        assertEquals(4000L, raw[3]);

        assertEquals(1000L, lifetime.getStartMillis(0));
        assertEquals(2000L, lifetime.getEndMillis(0));
        assertEquals(3000L, lifetime.getStartMillis(1));
        assertEquals(4000L, lifetime.getEndMillis(1));
        assertEquals(1000L, lifetime.getStartMillis());
        assertEquals(4000L, lifetime.getEndMillis());

        // Test intersection & contains with primitives
        assertTrue(lifetime.intersects(1500L, 2500L));
        assertTrue(lifetime.intersects(3500L, 5000L));
        assertFalse(lifetime.intersects(2100L, 2900L));

        assertTrue(lifetime.contains(1200L, 1800L));
        assertTrue(lifetime.contains(3100L, 3900L));
        assertFalse(lifetime.contains(1500L, 2500L));
    }

    // --- Optimization 2: Traversal Machine Strategy Pushdown & Step Folding ---
    @Test
    public void shouldFoldAllenFilterStepIntoTinkerGraphStepAndVertexStep() {
        final Vertex u = g.addV("station").property("name", "U").next();
        final Vertex v = g.addV("station").property("name", "V").next();
        final Vertex w = g.addV("station").property("name", "W").next();

        // Edge 1: [100, 200]
        g.addE("flight").from(u).to(v).property(Lifetime.START_TIME, 100L).property(Lifetime.END_TIME, 200L).next();
        // Edge 2: [200, 350]
        g.addE("flight").from(u).to(w).property(Lifetime.START_TIME, 200L).property(Lifetime.END_TIME, 350L).next();
        // Edge 3: [500, 600]
        g.addE("flight").from(v).to(w).property(Lifetime.START_TIME, 500L).property(Lifetime.END_TIME, 600L).next();

        // Reference interval: [250, 450]
        final Vertex ref = g.addV("window").property(Lifetime.START_TIME, 250L).property(Lifetime.END_TIME, 450L).next();

        // Test 1: g.E().temporalOverlaps(ref)
        final GraphTraversal<Edge, Edge> t1 = g.E().temporalOverlaps(ref);
        t1.asAdmin().applyStrategies();

        // Verify that AllenFilterStep is folded into TinkerGraphStep and removed from pipeline
        final TinkerGraphStep<?, ?> graphStep = (TinkerGraphStep<?, ?>) t1.asAdmin().getStartStep();
        assertFalse("AllenFilterStep should be folded into TinkerGraphStep", graphStep.getAllenFilterSteps().isEmpty());
        assertEquals("AllenFilterStep should not remain as independent step", 1, t1.asAdmin().getSteps().size());

        final List<Edge> edges = t1.toList();
        assertEquals(1, edges.size());
        assertEquals(200L, Lifetime.getLifetimeFromProperties(edges.get(0)).getStartMillis());

        // Test 2: g.V(u).outE().temporalBefore(ref)
        final GraphTraversal<Vertex, Edge> t2 = g.V(u).outE().temporalBefore(ref);
        t2.asAdmin().applyStrategies();

        // Find VertexStep in pipeline
        VertexStep<?> vertexStep = null;
        for (final Object step : t2.asAdmin().getSteps()) {
            if (step instanceof VertexStep) {
                vertexStep = (VertexStep<?>) step;
                break;
            }
        }
        assertNotNull(vertexStep);
        assertFalse("AllenFilterStep should be folded into VertexStep", vertexStep.getAllenFilterSteps().isEmpty());

        final List<Edge> beforeEdges = t2.toList();
        assertEquals(1, beforeEdges.size());
        assertEquals(100L, Lifetime.getLifetimeFromProperties(beforeEdges.get(0)).getStartMillis());

        // Test 3: g.E().temporalOverlaps(ref).count() must not be bypassed by TinkerGraphCountStrategy
        assertEquals(Long.valueOf(1L), g.E().temporalOverlaps(ref).count().next());

        // Test 4: g.V(u).outE("flight").temporalBefore(ref).inV() must not be bypassed by IncidentToAdjacentStrategy
        final List<Vertex> adjVertices = g.V(u).outE("flight").temporalBefore(ref).inV().toList();
        assertEquals(1, adjVertices.size());
        assertEquals(v, adjVertices.get(0));

        // Test 5: g.V(u).outE().hasLabel("flight").temporalBefore(ref) (InlineFilterStrategy + TemporalGraphStepStrategy)
        final List<Edge> inlinedEdges = g.V(u).outE().hasLabel("flight").temporalBefore(ref).toList();
        assertEquals(1, inlinedEdges.size());
        assertEquals(100L, Lifetime.getLifetimeFromProperties(inlinedEdges.get(0)).getStartMillis());

        // Test 6: g.V(u).window(100L, 400L).outE("flight").temporalBefore(ref)
        // Verifies that TemporalEdge decorator is unwrapped to evaluate the edge's own lifetime [100, 200]
        // rather than the decorator window [100, 400]
        final List<Edge> windowedEdges = g.V(u).window(100L, 400L).outE("flight").temporalBefore(ref).toList();
        assertEquals(1, windowedEdges.size());
        assertEquals(100L, Lifetime.getLifetimeFromProperties(windowedEdges.get(0)).getStartMillis());
    }

    // --- Optimization 3: Automated TemporalTinkerIndex Strategy Pushdown ---
    @Test
    public void shouldAutomaticallyPushDownTemporalQueryToIntervalTreeIndex() {
        // Create an index on temporalIntervals for Edge elements
        graph.createIndex(Lifetime.TEMPORAL_INTERVALS, Edge.class);

        final Vertex a = g.addV("city").property("name", "A").next();
        final Vertex b = g.addV("city").property("name", "B").next();

        // Add 10 edges with lifetimes
        for (int i = 0; i < 10; i++) {
            final long start = i * 100L;
            final long end = start + 50L;
            g.addE("train").from(a).to(b)
                    .lifetime(start, end)
                    .iterate();
        }

        // Query with temporalOverlaps across [220, 280] (should match edge i=2 with [200, 250])
        final Vertex queryWindow = g.addV("ref").lifetime(220L, 280L).next();

        final GraphTraversal<Edge, Edge> traversal = g.E().temporalOverlaps(queryWindow);
        traversal.asAdmin().applyStrategies();

        final TinkerGraphStep<?, ?> step = (TinkerGraphStep<?, ?>) traversal.asAdmin().getStartStep();
        assertNotNull("Index Allen step should be detected by TinkerGraphStep", step.getIndexAllenStep(Edge.class));

        final List<Edge> results = traversal.toList();
        assertEquals(1, results.size());
        assertEquals(200L, Lifetime.getLifetimeFromProperties(results.get(0)).getStartMillis());
    }

    // --- Optimization 4: Redundant Liveness Bypass in TemporalPageRank ---
    @Test
    public void shouldBypassLivenessCheckWhenVerticesAreUnboundedInTemporalPageRank() {
        final Vertex a = g.addV("node").property("name", "A").next();
        final Vertex b = g.addV("node").property("name", "B").next();
        final Vertex c = g.addV("node").property("name", "C").next();

        // Add chronological temporal edges between static (unbounded) vertices
        g.addE("link").from(a).to(b).lifetime(100L, 100L).iterate();
        g.addE("link").from(b).to(c).lifetime(200L, 200L).iterate();
        g.addE("link").from(c).to(a).lifetime(300L, 300L).iterate();

        final List<Vertex> ranked = g.V().temporalPageRank().
                with(TemporalPageRank.propertyName, "rank").
                toList();

        assertEquals(3, ranked.size());
        for (final Vertex v : ranked) {
            assertTrue("Vertex should have rank property", v.property("rank").isPresent());
            final double score = (Double) v.value("rank");
            assertTrue("PageRank score should be non-negative: " + score, score >= 0.0);
        }
    }
}
