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
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TemporalPageRankIntegrationTest {

    private static final int PAGE_RANK_ITERATIONS = 30;
    private static final String FAR_FUTURE_END_TIME = "9999-12-31";

    private TinkerGraph graph;
    private GraphTraversalSource g;
    private GraphTraversalSource computerG;

    @Before
    public void setUp() {
        graph = TinkerGraph.open();
        g = graph.traversal();
        computerG = graph.traversal().withComputer();
    }

    @Test
    public void testTemporalPageRankWithinClosedWindow() {
        loadTemporalPageRankFixture();

        final Map<String, Double> ranksByName = mapRanksByName(computerG.V()
                .temporalPageRank("2020-01-01", "2020-12-31")
                .with(PageRank.propertyName, "windowRank")
                .project("name", "windowRank")
                .by("name")
                .by(__.values("windowRank"))
                .toList());

        assertEquals(2, ranksByName.size());
        assertTrue(ranksByName.containsKey("alice"));
        assertTrue(ranksByName.containsKey("bob"));
        assertTrue(ranksByName.get("alice") > 0.0d);
        assertEquals(ranksByName.get("alice"), ranksByName.get("bob"), 0.00001d);
    }

    @Test
    public void testTemporalPageRankWithinOpenEndedWindow() {
        loadTemporalPageRankFixture();

        final Map<String, Double> ranksByName = mapRanksByName(computerG.V()
                .temporalPageRank("2021-01-01")
                .with(PageRank.propertyName, "windowRank")
                .project("name", "windowRank")
                .by("name")
                .by(__.values("windowRank"))
                .toList());

        assertEquals(2, ranksByName.size());
        assertTrue(ranksByName.containsKey("carol"));
        assertTrue(ranksByName.containsKey("dave"));
        assertTrue(ranksByName.get("carol") > 0.0d);
        assertEquals(ranksByName.get("carol"), ranksByName.get("dave"), 0.00001d);
    }

    @Test
    public void shouldPreservePlainPageRankAndChangeRanksForDifferentTemporalWindows() {
        final Vertex a = addVertex("a", "2020-01-01");
        final Vertex b = addVertex("b", "2020-01-01");
        final Vertex c = addVertex("c", "2020-01-01");
        final Vertex d = addVertex("d", "2021-01-01");

        addEdge(a, b, "link", "2020-01-01", FAR_FUTURE_END_TIME);
        addEdge(b, c, "link", "2020-01-01", FAR_FUTURE_END_TIME);
        addEdge(c, a, "link", "2020-01-01", FAR_FUTURE_END_TIME);

        addEdge(a, d, "link", "2021-01-01", FAR_FUTURE_END_TIME);
        addEdge(d, b, "link", "2021-01-01", FAR_FUTURE_END_TIME);
        addEdge(d, c, "link", "2021-01-01", FAR_FUTURE_END_TIME);

        final Map<String, Double> plainBeforeTemporal = computeRanks("rankAllBefore", null, null, null);
        final Map<String, Double> window2020 = computeRanks("rank2020", "2020-01-01", "2020-12-31", null);
        final Map<String, Double> window2021OpenEnded = computeRanks("rank2021", "2021-01-01", null, null);
        final Map<String, Double> plainAfterTemporal = computeRanks("rankAllAfter", null, null, null);

        assertPositiveRanks(plainBeforeTemporal);
        assertPositiveRanks(window2020);
        assertPositiveRanks(window2021OpenEnded);
        assertPositiveRanks(plainAfterTemporal);

        assertEquals(4, plainBeforeTemporal.size());
        assertEquals(3, window2020.size());
        assertEquals(4, window2021OpenEnded.size());
        assertEquals(4, plainAfterTemporal.size());

        assertFalse(window2020.containsKey("d"));
        assertTrue(window2021OpenEnded.containsKey("d"));

        assertTrue(window2020.get("a") > 0.0d);
        assertEquals(window2020.get("a"), window2020.get("b"), 0.02d);
        assertEquals(window2020.get("b"), window2020.get("c"), 0.02d);

        assertTrue(Math.abs(window2020.get("a") - window2021OpenEnded.get("a")) > 0.000000001d);

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
        addEdge(future, timelessA, "link", "2022-01-01", FAR_FUTURE_END_TIME);

        final Map<String, Double> plain = computeRanks("plainRank", null, null, null);
        final Map<String, Double> temporal = computeRanks("temporalRank", "2020-01-01", "2020-12-31", null);

        assertEquals(3, plain.size());
        assertEquals(2, temporal.size());
        assertTrue(temporal.containsKey("timelessA"));
        assertTrue(temporal.containsKey("timelessB"));
        assertFalse(temporal.containsKey("future"));

        assertPositiveRanks(temporal);
        assertTrue(temporal.get("timelessA") > 0.0d);
        assertEquals(temporal.get("timelessA"), temporal.get("timelessB"), 0.02d);
    }

    @Test
    public void shouldTreatLifetimeBoundariesAsInclusive() {
        final Vertex a = addVertex("a", "2020-01-01", FAR_FUTURE_END_TIME);
        final Vertex b = addVertex("b", "2019-01-01", "2020-12-31");
        final Vertex c = addVertex("c", "2020-12-31", "2020-12-31");

        addEdge(a, b, "link", "2020-01-01", FAR_FUTURE_END_TIME);
        addEdge(b, a, "link", "2019-01-01", "2020-12-31");
        addEdge(a, c, "link", "2020-12-31", "2020-12-31");
        addEdge(c, a, "link", "2020-12-31", "2020-12-31");

        final Map<String, Double> temporal = computeRanks("boundaryRank", "2020-01-01", "2020-12-31", null);

        assertEquals(3, temporal.size());
        assertTrue(temporal.containsKey("a"));
        assertTrue(temporal.containsKey("b"));
        assertTrue(temporal.containsKey("c"));

        assertPositiveRanks(temporal);
        assertTrue(temporal.get("c") > 0.0d);
    }

    @Test
    public void shouldFilterInactiveEdgesAndVerticesWhenUsingCustomEdgesTraversal() {
        final Vertex a = addVertex("a", "2020-01-01", "2020-12-31");
        final Vertex b = addVertex("b", "2020-01-01", "2020-12-31");
        final Vertex c = addVertex("c", "2020-01-01", "2020-12-31");
        final Vertex inactiveFuture = addVertex("inactiveFuture", "2021-01-01", FAR_FUTURE_END_TIME);

        addEdge(a, b, "link", "2020-01-01", "2020-12-31");
        addEdge(b, c, "link", "2020-01-01", "2020-12-31");
        addEdge(c, a, "link", "2020-01-01", "2020-12-31");

        addEdge(b, a, "link", "2021-01-01", "2021-12-31");
        addEdge(a, c, "ignored", "2020-01-01", "2020-12-31");

        final Map<String, Double> temporal = computeRanks("customRank", "2020-01-01", "2020-12-31",
                __.<Vertex>outE("link").asAdmin());

        assertEquals(3, temporal.size());
        assertTrue(temporal.containsKey("a"));
        assertTrue(temporal.containsKey("b"));
        assertTrue(temporal.containsKey("c"));
        assertFalse(temporal.containsKey("inactiveFuture"));

        assertPositiveRanks(temporal);
        assertTrue(temporal.get("a") > 0.0d);
        assertEquals(temporal.get("a"), temporal.get("b"), 0.02d);
        assertEquals(temporal.get("b"), temporal.get("c"), 0.02d);
    }

    @Test
    public void shouldProduceSameTemporalRanksRegardlessOfWhenCustomEdgesAreConfigured() {
        final Vertex a = addVertex("a", "2020-01-01", "2020-12-31");
        final Vertex b = addVertex("b", "2020-01-01", "2020-12-31");
        final Vertex c = addVertex("c", "2020-01-01", "2020-12-31");
        final Vertex future = addVertex("future", "2021-01-01", FAR_FUTURE_END_TIME);

        addEdge(a, b, "link", "2020-01-01", "2020-12-31");
        addEdge(a, c, "link", "2020-01-01", "2020-12-31");
        addEdge(b, c, "link", "2020-01-01", "2020-12-31");
        addEdge(c, a, "link", "2020-01-01", "2020-12-31");
        addEdge(future, a, "link", "2021-01-01", FAR_FUTURE_END_TIME);
        addEdge(a, future, "link", "2021-01-01", FAR_FUTURE_END_TIME);

        final Traversal.Admin<Vertex, Edge> inboundLinks = __.<Vertex>inE("link").asAdmin();

        final Map<String, Double> edgesBeforeOptions = computeRanks(
                "rankEdgesBeforeOptions", "2020-01-01", "2020-12-31", inboundLinks, true);
        final Map<String, Double> edgesAfterOptions = computeRanks(
                "rankEdgesAfterOptions", "2020-01-01", "2020-12-31", inboundLinks, false);

        assertEquals(3, edgesBeforeOptions.size());
        assertFalse(edgesBeforeOptions.containsKey("future"));
        assertPositiveRanks(edgesBeforeOptions);
        assertEquals(edgesBeforeOptions.keySet(), edgesAfterOptions.keySet());
        assertMatchingRanks(edgesBeforeOptions, edgesAfterOptions, 0.000001d);
    }

    @Test
    public void shouldRequireStartTimeForTemporalPageRank() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> this.computerG.V().temporalPageRank(null, "2020-12-31"));

        assertEquals("Start time cannot be null", exception.getMessage());
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

    private void loadTemporalPageRankFixture() {
        final Vertex alice = g.addV("person").property("name", "alice").lifetime("2020-01-01", "2020-12-31").next();
        final Vertex bob = g.addV("person").property("name", "bob").lifetime("2020-01-01", "2020-12-31").next();
        final Vertex carol = g.addV("person").property("name", "carol").lifetime("2022-01-01", "2022-12-31").next();
        final Vertex dave = g.addV("person").property("name", "dave").lifetime("2022-01-01", "2022-12-31").next();

        g.addE("knows").from(alice).to(bob).lifetime("2020-01-01", "2020-12-31").iterate();
        g.addE("knows").from(bob).to(alice).lifetime("2020-01-01", "2020-12-31").iterate();
        g.addE("knows").from(carol).to(dave).lifetime("2022-01-01", "2022-12-31").iterate();
        g.addE("knows").from(dave).to(carol).lifetime("2022-01-01", "2022-12-31").iterate();
    }

    private Vertex addVertex(final String name) {
        return this.graph.addVertex("name", name);
    }

    private Vertex addVertex(final String name, final String startTime) {
        return addVertex(name, startTime, FAR_FUTURE_END_TIME);
    }

    private Vertex addVertex(final String name, final String startTime, final String endTime) {
        return this.graph.addVertex("name", name, "startTime", startTime, "endTime", endTime);
    }

    private void addEdge(final Vertex out, final Vertex in, final String label) {
        out.addEdge(label, in);
    }

    private void addEdge(final Vertex out, final Vertex in, final String label, final String startTime,
                         final String endTime) {
        out.addEdge(label, in, "startTime", startTime, "endTime", endTime);
    }

    private Map<String, Double> computeRanks(final String propertyName, final String startTime, final String endTime,
                                             final Traversal.Admin<Vertex, Edge> edgeTraversal) {
        return computeRanks(propertyName, startTime, endTime, edgeTraversal, true);
    }

    private Map<String, Double> computeRanks(final String propertyName, final String startTime, final String endTime,
                                             final Traversal.Admin<Vertex, Edge> edgeTraversal,
                                             final boolean configureEdgesBeforeOptions) {
        GraphTraversal<Vertex, Vertex> traversal =
                null == startTime ? this.computerG.V().pageRank() :
                        (null == endTime ? this.computerG.V().temporalPageRank(startTime) :
                                this.computerG.V().temporalPageRank(startTime, endTime));

        if (configureEdgesBeforeOptions && null != edgeTraversal) {
            traversal = traversal.with(PageRank.edges, edgeTraversal.clone());
        }

        traversal = traversal.with(PageRank.propertyName, propertyName)
                .with(PageRank.times, PAGE_RANK_ITERATIONS);

        if (!configureEdgesBeforeOptions && null != edgeTraversal) {
            traversal = traversal.with(PageRank.edges, edgeTraversal.clone());
        }

        final Map<String, Double> ranks = new LinkedHashMap<>();
        traversal.has(propertyName).forEachRemaining(vertex -> ranks.put(vertex.value("name"), vertex.<Double>value(propertyName)));
        return ranks;
    }

    private void assertMatchingRanks(final Map<String, Double> expected, final Map<String, Double> actual,
                                     final double delta) {
        assertEquals(expected.size(), actual.size());
        expected.forEach((name, rank) -> assertEquals(rank, actual.get(name), delta));
    }

    private void assertPositiveRanks(final Map<String, Double> ranks) {
        assertFalse(ranks.isEmpty());
        ranks.values().forEach(rank -> assertTrue(rank > 0.0d));
    }

    private static Map<String, Double> mapRanksByName(final List<Map<String, Object>> results) {
        final Map<String, Double> ranksByName = new LinkedHashMap<>();
        results.forEach(result -> ranksByName.put((String) result.get("name"), ((Number) result.get("windowRank")).doubleValue()));
        return ranksByName;
    }
}
