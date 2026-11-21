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

import org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget;
import org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalShortestPathVertexProgram;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class TemporalBfsIntegrationTest {

    @Test
    public void shouldFindEarliestArrival() {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        Vertex a = g.addV("airport").property("name", "A").next();
        Vertex b = g.addV("airport").property("name", "B").next();
        Vertex c = g.addV("airport").property("name", "C").next();

        // A -> B (Flight 1, 1000 to 2000)
        g.addE("flight").from(a).to(b).property("temporalIntervals", "1000:2000").next();
        // B -> C (Flight 2, departs 2500, arrives 3000) - Valid connection
        g.addE("flight").from(b).to(c).property("temporalIntervals", "2500:3000").next();
        // B -> C (Flight 3, departs 1500, arrives 1800) - Invalid connection (departs before we arrive at B)
        g.addE("flight").from(b).to(c).property("temporalIntervals", "1500:1800").next();
        // A -> C (Flight 4, departs 1200, arrives 4000) - Direct but arrives very late
        g.addE("flight").from(a).to(c).property("temporalIntervals", "1200:4000").next();

        Map<Vertex, Long> result = g.V(a).tbfs(0, TemporalPathTarget.EARLIEST_ARRIVAL, Direction.OUT, "flight").next();

        // We expect to reach B at 2000
        assertEquals(Long.valueOf(2000L), result.get(b));
        // We expect to reach C at 3000 (via Flight 2). Flight 4 would arrive at 4000.
        assertEquals(Long.valueOf(3000L), result.get(c));
    }

    @Test
    public void shouldFindEarliestDeparture() {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        Vertex a = g.addV("airport").property("name", "A").next();
        Vertex b = g.addV("airport").property("name", "B").next();
        Vertex c = g.addV("airport").property("name", "C").next();

        // A -> B (Flight 1, 1000 to 2000)
        g.addE("flight").from(a).to(b).property("temporalIntervals", "1000:2000").next();
        // B -> C (Flight 2, departs 2500, arrives 3000)
        g.addE("flight").from(b).to(c).property("temporalIntervals", "2500:3000").next();
        
        // A -> C (Flight 4, departs 1200, arrives 4000)
        g.addE("flight").from(a).to(c).property("temporalIntervals", "1200:4000").next();

        Map<Vertex, Long> result = g.V(a).tbfs(0, TemporalPathTarget.EARLIEST_DEPARTURE, Direction.OUT, "flight").next();

        // B departs at 1000
        assertEquals(Long.valueOf(1000L), result.get(b));
        // C departs at 1200 (Flight 4 from A is earlier than Flight 2 from B at 2500)
        assertEquals(Long.valueOf(1200L), result.get(c));
    }

    @Test
    public void shouldFindLatestDeparture() {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        Vertex a = g.addV("airport").property("name", "A").next();
        Vertex b = g.addV("airport").property("name", "B").next();
        Vertex c = g.addV("airport").property("name", "C").next();

        // A -> B (Flight 1, departs 1000, arrives 2000)
        g.addE("flight").from(a).to(b).property("temporalIntervals", "1000:2000").next();
        // A -> B (Flight 5, departs 1800, arrives 2400)
        g.addE("flight").from(a).to(b).property("temporalIntervals", "1800:2400").next();

        // B -> C (Flight 2, departs 2500, arrives 3000)
        g.addE("flight").from(b).to(c).property("temporalIntervals", "2500:3000").next();
        // B -> C (Flight 3, departs 3500, arrives 4000)
        g.addE("flight").from(b).to(c).property("temporalIntervals", "3500:4000").next();

        // Deadline is 3200 (so Flight 3 is too late!). 
        // We must arrive at C by 3200.
        // That means we must take Flight 2 (arrives 3000).
        // Flight 2 departs B at 2500.
        // We must arrive at B by 2500.
        // Flight 5 arrives at 2400 (valid!), departs at 1800.
        // Flight 1 arrives at 2000 (valid!), departs at 1000.
        // We want LATEST_DEPARTURE, so we prefer Flight 5!
        
        // Target is C, but we are traversing backwards to find when we should depart from A
        Map<Vertex, Long> result = g.V(c).tbfs(3200, TemporalPathTarget.LATEST_DEPARTURE, Direction.IN, "flight").next();

        // The latest we can depart from A is 1800.
        assertEquals(Long.valueOf(1800L), result.get(a));
    }

    @Test
    public void shouldSupportNegativeEpochTimestampsInOltpAndOlap() {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        Vertex a = g.addV("airport").property("name", "A").next();
        Vertex b = g.addV("airport").property("name", "B").next();
        Vertex c = g.addV("airport").property("name", "C").next();

        // Pre-1970 negative epoch timestamps (including -1L sentinel boundary)
        g.addE("flight").from(a).to(b).lifetime(-5000L, -3000L).next();
        g.addE("flight").from(b).to(c).lifetime(-2000L, -1L).next();

        Map<Vertex, Long> earliestArrival = g.V(a).tbfs(-6000L, TemporalPathTarget.EARLIEST_ARRIVAL, Direction.OUT, "flight").next();
        assertEquals(Long.valueOf(-3000L), earliestArrival.get(b));
        assertEquals(Long.valueOf(-1L), earliestArrival.get(c));

        Map<Vertex, Long> latestDeparture = g.V(c).tbfs(0L, TemporalPathTarget.LATEST_DEPARTURE, Direction.IN, "flight").next();
        assertEquals(Long.valueOf(-2000L), latestDeparture.get(b));
        assertEquals(Long.valueOf(-5000L), latestDeparture.get(a));

        Map<Vertex, Long> latestArrival = g.V(c).tbfs(0L, TemporalPathTarget.LATEST_ARRIVAL, Direction.IN, "flight").next();
        assertEquals(Long.valueOf(-1L), latestArrival.get(b));
        assertEquals(Long.valueOf(-3000L), latestArrival.get(a));

        assertEquals(3, g.withComputer().V(a)
                .temporalShortestPath(TemporalPathTarget.EARLIEST_ARRIVAL, -6000L)
                .toList().size());
        assertEquals(3, g.withComputer().V(c)
                .temporalShortestPath(TemporalPathTarget.LATEST_DEPARTURE, 0L)
                .toList().size());
    }

    @Test
    public void shouldDecoupleCausalTimeFromTargetMetricInOlapTemporalShortestPath() {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        Vertex a = g.addV("airport").property("name", "A").next();
        Vertex b = g.addV("airport").property("name", "B").next();
        Vertex c = g.addV("airport").property("name", "C").next();

        // Edge 1: departs earlier (100) so it wins EARLIEST_DEPARTURE at B, but arrives too late (500) for B->C
        // Edge 2: departs slightly later (120), but arrives earlier (200) so it unlocks B->C [250, 300)
        g.addE("flight").from(a).to(b)
                .lifetime(100L, 500L)
                .next();
        g.addE("flight").from(a).to(b)
                .lifetime(120L, 200L)
                .next();
        g.addE("flight").from(b).to(c)
                .lifetime(250L, 300L)
                .next();

        Map<Vertex, Long> oltpResult = g.V(a).tbfs(0L, TemporalPathTarget.EARLIEST_DEPARTURE, Direction.OUT, "flight").next();
        assertEquals(Long.valueOf(100L), oltpResult.get(b));
        assertEquals(Long.valueOf(250L), oltpResult.get(c));

        // OLAP TemporalShortestPathVertexProgram must also reach C (paths to A, B, and C = 3 paths)
        assertEquals(3, g.withComputer().V(a)
                .temporalShortestPath(TemporalPathTarget.EARLIEST_DEPARTURE, 0L)
                .toList().size());
    }

    @Test
    public void shouldPreserveMultiVersionLifetimePropertiesOnSameKey() {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        Vertex alice = g.addV("person").property("name", "alice")
                .lifetimeProperty("title", "Junior", "2020-01-01", "2022-01-01")
                .lifetimeProperty("title", "Senior", "2022-01-01", "2024-01-01")
                .next();

        assertEquals("Junior", g.V(alice).atTime("2021-01-01").values("title").next());
        assertEquals("Senior", g.V(alice).atTime("2023-01-01").values("title").next());
        assertEquals(2, g.V(alice).window("2020-01-01", "2024-01-01").values("title").toList().size());

        // Non-String property values via Object overload
        g.V(alice).lifetimeProperty("level", 1, "2020-01-01", "2022-01-01")
                .lifetimeProperty("level", 2, "2022-01-01", "2024-01-01").iterate();
        assertEquals(Integer.valueOf(1), g.V(alice).atTime("2021-01-01").values("level").next());
        assertEquals(Integer.valueOf(2), g.V(alice).atTime("2023-01-01").values("level").next());
    }

    @Test
    public void shouldConfigureTemporalShortestPathViaWithModulator() {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        Vertex a = g.addV("airport").property("name", "A").next();
        Vertex b = g.addV("airport").property("name", "B").next();
        Vertex c = g.addV("airport").property("name", "C").next();

        g.addE("flight").from(a).to(b).lifetime(1000L, 2000L).next();
        g.addE("flight").from(b).to(c).lifetime(2500L, 3000L).next();

        // Configure TARGET_VERTEX_FILTER, TARGET, INITIAL_TIME, edges, and includeEdges via .with(...)
        List<Path> paths = g.withComputer().V(a)
                .temporalShortestPath()
                .with(TemporalShortestPathVertexProgram.TARGET, "EARLIEST_ARRIVAL")
                .with(TemporalShortestPathVertexProgram.INITIAL_TIME, 1000L)
                .with(TemporalShortestPathVertexProgram.TARGET_VERTEX_FILTER, __.has("name", "C"))
                .with("edges", Direction.OUT)
                .with("includeEdges", true)
                .toList();

        assertEquals(1, paths.size());
        // With includeEdges=true, path A -> e1 -> B -> e2 -> C has 5 elements
        assertEquals(5, paths.get(0).size());

        // Initial time after A->B starts (1500 > 1000) should find no path to C
        List<Path> emptyPaths = g.withComputer().V(a)
                .temporalShortestPath()
                .with(TemporalShortestPathVertexProgram.TARGET, TemporalPathTarget.EARLIEST_ARRIVAL)
                .with(TemporalShortestPathVertexProgram.INITIAL_TIME, 1500L)
                .with("target", __.has("name", "C"))
                .toList();
        assertEquals(0, emptyPaths.size());
    }

    @Test
    public void shouldDefaultTwoArgTbfsToInDirectionForBackwardTargets() {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        Vertex a = g.addV("airport").property("name", "A").next();
        Vertex b = g.addV("airport").property("name", "B").next();
        Vertex c = g.addV("airport").property("name", "C").next();

        g.addE("flight").from(a).to(b).lifetime(1000L, 2000L).next();
        g.addE("flight").from(b).to(c).lifetime(2500L, 3000L).next();

        Map<Vertex, Long> result = g.V(c).tbfs(TemporalPathTarget.LATEST_DEPARTURE, 3200L).next();
        assertEquals(Long.valueOf(2500L), result.get(b));
        assertEquals(Long.valueOf(1000L), result.get(a));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullTargetInTbfs() {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();
        Vertex a = g.addV("airport").next();
        g.V(a).tbfs(null, 1000L).toList();
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullDirectionInTbfs() {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();
        Vertex a = g.addV("airport").next();
        g.V(a).tbfs(1000L, TemporalPathTarget.EARLIEST_ARRIVAL, null).toList();
    }

    @Test
    public void shouldDeduplicateParallelEqualTimePathsInOlapTemporalShortestPath() {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        Vertex a = g.addV("airport").property("name", "A").next();
        Vertex b = g.addV("airport").property("name", "B").next();
        Vertex c = g.addV("airport").property("name", "C").next();

        // Two parallel identical-interval edges A->B
        g.addE("flight").from(a).to(b).lifetime(1000L, 2000L).next();
        g.addE("flight").from(a).to(b).lifetime(1000L, 2000L).next();
        g.addE("flight").from(b).to(c).lifetime(2500L, 3000L).next();

        List<Path> paths = g.withComputer().V(a)
                .temporalShortestPath(TemporalPathTarget.EARLIEST_ARRIVAL, 1000L)
                .with("target", __.has("name", "C"))
                .with("edges", Direction.OUT)
                .toList();
        assertEquals(1, paths.size());
        assertEquals(3, paths.get(0).size());
    }

    @Test
    public void shouldSupportShortestPathOptionConstantsDateOverloadsAndAnonymousFactories() {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        Vertex a = g.addV("airport").property("name", "A").next();
        Vertex b = g.addV("airport").property("name", "B").next();
        Vertex c = g.addV("airport").property("name", "C").next();

        g.addE("flight").from(a).to(b).lifetime(1000L, 2000L).next();
        g.addE("flight").from(b).to(c).lifetime(2500L, 3000L).next();

        // ShortestPath.* keys (maxDistance=1 filters out 2-hop path to C)
        List<Path> maxDistOne = g.withComputer().V(a)
                .temporalShortestPath(TemporalPathTarget.EARLIEST_ARRIVAL, new java.util.Date(1000L))
                .with(org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.ShortestPath.target, __.has("name", "C"))
                .with(org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.ShortestPath.edges, Direction.OUT)
                .with(org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.ShortestPath.maxDistance, 1)
                .toList();
        assertEquals(0, maxDistOne.size());

        // ShortestPath.maxDistance=2 allows 2-hop path A -> B -> C with Date INITIAL_TIME
        List<Path> maxDistTwo = g.withComputer().V(a)
                .temporalShortestPath()
                .with(TemporalShortestPathVertexProgram.INITIAL_TIME, new java.util.Date(1000L))
                .with(org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.ShortestPath.target, __.has("name", "C"))
                .with(org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.ShortestPath.edges, Direction.OUT)
                .with(org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.ShortestPath.maxDistance, 2)
                .with(org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.ShortestPath.includeEdges, true)
                .toList();
        assertEquals(1, maxDistTwo.size());
        assertEquals(5, maxDistTwo.get(0).size());

        // 2-arg Date overload and anonymous __.tbfs(...)
        Map<Vertex, Long> dateResult = g.V(c).tbfs(TemporalPathTarget.LATEST_ARRIVAL, new java.util.Date(3200L)).next();
        assertEquals(Long.valueOf(3000L), dateResult.get(b));
        assertEquals(Long.valueOf(2000L), dateResult.get(a));

        Map<Vertex, Long> anonResult = g.V(a).local(__.tbfs(TemporalPathTarget.EARLIEST_ARRIVAL, 1000L)).next();
        assertEquals(Long.valueOf(3000L), anonResult.get(c));

        // Null Date rejection on tbfs and temporalShortestPath
        try {
            g.V(a).tbfs(TemporalPathTarget.EARLIEST_ARRIVAL, (java.util.Date) null).toList();
            org.junit.Assert.fail("Expected IllegalArgumentException for null Date in tbfs");
        } catch (final IllegalArgumentException expected) {
            // expected
        }
        try {
            g.V(a).temporalShortestPath(TemporalPathTarget.EARLIEST_ARRIVAL, (java.util.Date) null).toList();
            org.junit.Assert.fail("Expected IllegalArgumentException for null Date in temporalShortestPath");
        } catch (final IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void shouldSupportWindowedOlapTemporalShortestPathWithDetachedMessages() throws Exception {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        Vertex a = g.addV("airport").property("name", "A").next();
        Vertex b = g.addV("airport").property("name", "B").next();
        Vertex c = g.addV("airport").property("name", "C").next();

        // Edge A->B active in [1000, 2000) and [5000, 6000); edge B->C active in [2500, 3000)
        g.addE("flight").from(a).to(b).lifetime(1000L, 2000L).addInterval(5000L, 6000L).next();
        g.addE("flight").from(b).to(c).lifetime(2500L, 3000L).next();

        // Windowed EARLIEST_DEPARTURE with includeEdges=true must succeed and emit ReferenceEdge/ReferenceVertex in Path
        List<Path> paths = g.withComputer().V(a)
                .window(500L, 4000L)
                .temporalShortestPath(TemporalPathTarget.EARLIEST_DEPARTURE, 1000L)
                .with("target", __.has("name", "C"))
                .with("edges", Direction.OUT)
                .with("includeEdges", true)
                .toList();
        assertEquals(1, paths.size());
        assertEquals(5, paths.get(0).size());

        // Verify the message payload structure Triplet<Path, Edge, Pair<Long, Long>> round-trips through Gryo V3
        final org.apache.tinkerpop.gremlin.structure.io.gryo.GryoMapper mapper =
                org.apache.tinkerpop.gremlin.structure.io.gryo.GryoMapper.build()
                        .version(org.apache.tinkerpop.gremlin.structure.io.gryo.GryoVersion.V3_0).create();
        final org.apache.tinkerpop.gremlin.structure.io.gryo.GryoWriter writer =
                org.apache.tinkerpop.gremlin.structure.io.gryo.GryoWriter.build().mapper(mapper).create();
        final org.apache.tinkerpop.gremlin.structure.io.gryo.GryoReader reader =
                org.apache.tinkerpop.gremlin.structure.io.gryo.GryoReader.build().mapper(mapper).create();
        final org.javatuples.Triplet<Path, org.apache.tinkerpop.gremlin.structure.Edge, org.javatuples.Pair<Long, Long>> msg =
                org.javatuples.Triplet.with(paths.get(0),
                        (org.apache.tinkerpop.gremlin.structure.Edge) paths.get(0).get(1),
                        org.javatuples.Pair.with(1000L, 2000L));
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writer.writeObject(out, msg);
        final Object deserialized = reader.readObject(new java.io.ByteArrayInputStream(out.toByteArray()), Object.class);
        assertEquals(msg, deserialized);
    }
}
