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
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TemporalPageRankIntegrationTest {

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

    private static Map<String, Double> mapRanksByName(final List<Map<String, Object>> results) {
        final Map<String, Double> ranksByName = new LinkedHashMap<>();
        results.forEach(result -> ranksByName.put((String) result.get("name"), ((Number) result.get("windowRank")).doubleValue()));
        return ranksByName;
    }
}
