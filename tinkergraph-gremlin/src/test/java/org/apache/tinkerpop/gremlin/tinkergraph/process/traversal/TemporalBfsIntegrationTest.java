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
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Test;

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
}
