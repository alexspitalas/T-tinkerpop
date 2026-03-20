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

import org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.PageRank;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.LifetimeHelper;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PageRankLifetimeIntegrationTest {

    private static final int PAGE_RANK_ITERATIONS = 30;

    private TinkerGraph graph;
    private GraphTraversalSource g;

    @Before
    public void setUp() {
        this.graph = TinkerGraph.open();
        this.g = this.graph.traversal();
    }

    @Test
    public void shouldPreservePlainPageRankAndChangeRanksForDifferentTemporalWindows() {
        final Vertex a = addVertex("a", "2020-01-01");
        final Vertex b = addVertex("b", "2020-01-01");
        final Vertex c = addVertex("c", "2020-01-01");
        final Vertex d = addVertex("d", "2021-01-01");

        addEdge(a, b, "link", "2020-01-01", LifetimeHelper.DEFAULT_ENDTIME);
        addEdge(b, c, "link", "2020-01-01", LifetimeHelper.DEFAULT_ENDTIME);
        addEdge(c, a, "link", "2020-01-01", LifetimeHelper.DEFAULT_ENDTIME);

        addEdge(a, d, "link", "2021-01-01", LifetimeHelper.DEFAULT_ENDTIME);
        addEdge(d, b, "link", "2021-01-01", LifetimeHelper.DEFAULT_ENDTIME);
        addEdge(d, c, "link", "2021-01-01", LifetimeHelper.DEFAULT_ENDTIME);

        final Map<String, Double> plainBeforeTemporal = computeRanks("rankAllBefore", null, null, null);
        final Map<String, Double> window2020 = computeRanks("rank2020", "2020-01-01", "2020-12-31", null);
        final Map<String, Double> window2021OpenEnded = computeRanks("rank2021", "2021-01-01", null, null);
        final Map<String, Double> plainAfterTemporal = computeRanks("rankAllAfter", null, null, null);

        assertRankMass(plainBeforeTemporal);
        assertRankMass(window2020);
        assertRankMass(window2021OpenEnded);
        assertRankMass(plainAfterTemporal);

        assertEquals(4, plainBeforeTemporal.size());
        assertEquals(3, window2020.size());
        assertEquals(4, window2021OpenEnded.size());
        assertEquals(4, plainAfterTemporal.size());

        assertFalse(window2020.containsKey("d"));
        assertTrue(window2021OpenEnded.containsKey("d"));

        assertEquals(1.0d / 3.0d, window2020.get("a"), 0.02d);
        assertEquals(window2020.get("a"), window2020.get("b"), 0.02d);
        assertEquals(window2020.get("b"), window2020.get("c"), 0.02d);

        assertTrue(window2021OpenEnded.get("b") > window2021OpenEnded.get("d"));
        assertTrue(window2021OpenEnded.get("c") > window2021OpenEnded.get("d"));
        assertTrue(Math.abs(window2020.get("a") - window2021OpenEnded.get("a")) > 0.02d);

        assertMatchingRanks(plainBeforeTemporal, window2021OpenEnded, 0.000001d);
        assertMatchingRanks(plainBeforeTemporal, plainAfterTemporal, 0.000001d);
    }

    @Test
    public void shouldTreatElementsWithoutTemporalPropertiesAsAlwaysActive() {
        final Vertex timelessA = addVertex("timelessA");
        final Vertex timelessB = addVertex("timelessB");
        final Vertex future = addVertex("future", "2022-01-01");

        addEdge(timelessA, timelessB, "link");
        addEdge(timelessB, timelessA, "link");
        addEdge(future, timelessA, "link", "2022-01-01", LifetimeHelper.DEFAULT_ENDTIME);

        final Map<String, Double> plain = computeRanks("plainRank", null, null, null);
        final Map<String, Double> temporal = computeRanks("temporalRank", "2020-01-01", "2020-12-31", null);

        assertEquals(3, plain.size());
        assertEquals(2, temporal.size());
        assertTrue(temporal.containsKey("timelessA"));
        assertTrue(temporal.containsKey("timelessB"));
        assertFalse(temporal.containsKey("future"));

        assertRankMass(temporal);
        assertEquals(0.5d, temporal.get("timelessA"), 0.02d);
        assertEquals(temporal.get("timelessA"), temporal.get("timelessB"), 0.02d);
    }

    @Test
    public void shouldTreatLifetimeBoundariesAsInclusive() {
        final Vertex a = addVertex("a", "2020-01-01", LifetimeHelper.DEFAULT_ENDTIME);
        final Vertex b = addVertex("b", "2019-01-01", "2020-12-31");
        final Vertex c = addVertex("c", "2020-12-31", "2020-12-31");

        addEdge(a, b, "link", "2020-01-01", LifetimeHelper.DEFAULT_ENDTIME);
        addEdge(b, a, "link", "2019-01-01", "2020-12-31");
        addEdge(a, c, "link", "2020-12-31", "2020-12-31");
        addEdge(c, a, "link", "2020-12-31", "2020-12-31");

        final Map<String, Double> temporal = computeRanks("boundaryRank", "2020-01-01", "2020-12-31", null);

        assertEquals(3, temporal.size());
        assertTrue(temporal.containsKey("a"));
        assertTrue(temporal.containsKey("b"));
        assertTrue(temporal.containsKey("c"));

        assertRankMass(temporal);
        assertTrue(temporal.get("a") > temporal.get("b"));
        assertTrue(temporal.get("c") > 0.0d);
    }

    @Test
    public void shouldFilterInactiveEdgesAndVerticesWhenUsingCustomEdgesTraversal() {
        final Vertex a = addVertex("a", "2020-01-01", "2020-12-31");
        final Vertex b = addVertex("b", "2020-01-01", "2020-12-31");
        final Vertex c = addVertex("c", "2020-01-01", "2020-12-31");
        final Vertex inactiveOut = addVertex("inactiveOut", "2021-01-01", LifetimeHelper.DEFAULT_ENDTIME);
        final Vertex inactiveIn = addVertex("inactiveIn", "2021-01-01", LifetimeHelper.DEFAULT_ENDTIME);

        addEdge(a, b, "link", "2020-01-01", "2020-12-31");
        addEdge(b, c, "link", "2020-01-01", "2020-12-31");
        addEdge(c, a, "link", "2020-01-01", "2020-12-31");

        addEdge(inactiveOut, a, "link", "2020-01-01", "2020-12-31");
        addEdge(a, inactiveIn, "link", "2020-01-01", "2020-12-31");
        addEdge(b, a, "link", "2021-01-01", "2021-12-31");
        addEdge(a, c, "ignored", "2020-01-01", "2020-12-31");

        final Map<String, Double> temporal = computeRanks("customRank", "2020-01-01", "2020-12-31", __.<Vertex>outE("link").asAdmin());

        assertEquals(3, temporal.size());
        assertTrue(temporal.containsKey("a"));
        assertTrue(temporal.containsKey("b"));
        assertTrue(temporal.containsKey("c"));
        assertFalse(temporal.containsKey("inactiveOut"));
        assertFalse(temporal.containsKey("inactiveIn"));

        assertRankMass(temporal);
        assertEquals(1.0d / 3.0d, temporal.get("a"), 0.02d);
        assertEquals(temporal.get("a"), temporal.get("b"), 0.02d);
        assertEquals(temporal.get("b"), temporal.get("c"), 0.02d);
    }

    @Test
    public void shouldProduceSameTemporalRanksRegardlessOfWhenCustomEdgesAreConfigured() {
        final Vertex a = addVertex("a", "2020-01-01", "2020-12-31");
        final Vertex b = addVertex("b", "2020-01-01", "2020-12-31");
        final Vertex c = addVertex("c", "2020-01-01", "2020-12-31");
        final Vertex future = addVertex("future", "2021-01-01", LifetimeHelper.DEFAULT_ENDTIME);

        addEdge(a, b, "link", "2020-01-01", "2020-12-31");
        addEdge(a, c, "link", "2020-01-01", "2020-12-31");
        addEdge(b, c, "link", "2020-01-01", "2020-12-31");
        addEdge(c, a, "link", "2020-01-01", "2020-12-31");
        addEdge(future, a, "link", "2020-01-01", "2020-12-31");
        addEdge(a, future, "link", "2020-01-01", "2020-12-31");

        final Traversal.Admin<Vertex, Edge> inboundLinks = __.<Vertex>inE("link").asAdmin();

        final Map<String, Double> edgesBeforeTime = computeRanks(
                "rankEdgesBeforeTime", "2020-01-01", "2020-12-31", inboundLinks, true);
        final Map<String, Double> edgesAfterTime = computeRanks(
                "rankEdgesAfterTime", "2020-01-01", "2020-12-31", inboundLinks, false);

        assertEquals(3, edgesBeforeTime.size());
        assertFalse(edgesBeforeTime.containsKey("future"));
        assertRankMass(edgesBeforeTime);
        assertEquals(edgesBeforeTime.keySet(), edgesAfterTime.keySet());
        assertMatchingRanks(edgesBeforeTime, edgesAfterTime, 0.000001d);
    }

    @Test
    public void shouldReturnNoRanksWhenNoVerticesAreActiveInWindow() {
        final Vertex a = addVertex("a", "2020-01-01", "2020-12-31");
        final Vertex b = addVertex("b", "2020-01-01", "2020-12-31");

        addEdge(a, b, "link", "2020-01-01", "2020-12-31");
        addEdge(b, a, "link", "2020-01-01", "2020-12-31");

        final Map<String, Double> temporal = computeRanks("emptyRank", "2030-01-01", "2030-12-31", null);

        assertTrue(temporal.isEmpty());
    }

    private Vertex addVertex(final String name) {
        return this.graph.addVertex("name", name);
    }

    private Vertex addVertex(final String name, final String startTime) {
        return addVertex(name, startTime, LifetimeHelper.DEFAULT_ENDTIME);
    }

    private Vertex addVertex(final String name, final String startTime, final String endTime) {
        return this.graph.addVertex("name", name, LifetimeHelper.START_TIME, startTime, LifetimeHelper.END_TIME, endTime);
    }

    private void addEdge(final Vertex out, final Vertex in, final String label) {
        out.addEdge(label, in);
    }

    private void addEdge(final Vertex out, final Vertex in, final String label, final String startTime, final String endTime) {
        out.addEdge(label, in, LifetimeHelper.START_TIME, startTime, LifetimeHelper.END_TIME, endTime);
    }

    private Map<String, Double> computeRanks(final String propertyName, final String startTime, final String endTime,
                                             final Traversal.Admin<Vertex, Edge> edgeTraversal) {
        return computeRanks(propertyName, startTime, endTime, edgeTraversal, true);
    }

    private Map<String, Double> computeRanks(final String propertyName, final String startTime, final String endTime,
                                             final Traversal.Admin<Vertex, Edge> edgeTraversal,
                                             final boolean configureEdgesBeforeTemporalOptions) {
        GraphTraversal<Vertex, Vertex> traversal = this.g.withComputer().V().pageRank()
                .with(PageRank.propertyName, propertyName)
                .with(PageRank.times, PAGE_RANK_ITERATIONS);
        if (configureEdgesBeforeTemporalOptions) {
            if (null != edgeTraversal) {
                traversal = traversal.with(PageRank.edges, edgeTraversal.clone());
            }
            if (null != startTime) {
                traversal = traversal.with(PageRank.startTime, startTime);
            }
            if (null != endTime) {
                traversal = traversal.with(PageRank.endTime, endTime);
            }
        } else {
            if (null != startTime) {
                traversal = traversal.with(PageRank.startTime, startTime);
            }
            if (null != endTime) {
                traversal = traversal.with(PageRank.endTime, endTime);
            }
            if (null != edgeTraversal) {
                traversal = traversal.with(PageRank.edges, edgeTraversal.clone());
            }
        }

        final Map<String, Double> ranks = new LinkedHashMap<>();
        traversal.has(propertyName).forEachRemaining(vertex -> ranks.put(vertex.value("name"), vertex.<Double>value(propertyName)));
        return ranks;
    }

    private void assertMatchingRanks(final Map<String, Double> expected, final Map<String, Double> actual, final double delta) {
        assertEquals(expected.size(), actual.size());
        expected.forEach((name, rank) -> assertEquals(rank, actual.get(name), delta));
    }

    private void assertRankMass(final Map<String, Double> ranks) {
        final double total = ranks.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0d, total, 0.01d);
    }
}
