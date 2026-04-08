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
package org.apache.tinkerpop.gremlin.tinkergraph.process.computer.ranking.pagerank;

import org.apache.tinkerpop.gremlin.process.computer.ComputerResult;
import org.apache.tinkerpop.gremlin.process.computer.ranking.pagerank.PageRankVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.ranking.pagerank.TemporalPageRankVertexProgram;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TemporalPageRankVertexProgramTest {

    @Test
    public void shouldRespectPreviousIntervalContextWhenDistributingRank() throws Exception {
        final TinkerGraph normalGraph = createTemporalGraph();
        final TinkerGraph temporalGraph = createTemporalGraph();

        final ComputerResult normalResult = normalGraph.compute()
                .program(PageRankVertexProgram.build().alpha(1.0d).iterations(4).property("plainRank").create(normalGraph))
                .submit().get();
        final ComputerResult temporalResult = temporalGraph.compute()
                .program(TemporalPageRankVertexProgram.build().alpha(1.0d).iterations(4).property("temporalRank").create(temporalGraph))
                .submit().get();

        final double plainRankAtTarget = normalResult.graph().traversal().V().has("name", "target").<Double>values("plainRank").next();
        final double temporalRankAtTarget = temporalResult.graph().traversal().V().has("name", "target").<Double>values("temporalRank").next();

        assertTrue("Temporal PageRank should block the late arrival at the bridge from following the middle interval",
                temporalRankAtTarget < plainRankAtTarget);
    }

    @Test
    public void shouldMatchNormalPageRankWhenAllTemporalPathsAreSequential() throws Exception {
        final TinkerGraph normalGraph = createSequentialGraph();
        final TinkerGraph temporalGraph = createSequentialGraph();

        final ComputerResult normalResult = normalGraph.compute()
                .program(PageRankVertexProgram.build().alpha(1.0d).iterations(5).property("plainRank").create(normalGraph))
                .submit().get();
        final ComputerResult temporalResult = temporalGraph.compute()
                .program(TemporalPageRankVertexProgram.build().alpha(1.0d).iterations(5).property("temporalRank").create(temporalGraph))
                .submit().get();

        assertVertexRankEquals(normalResult, temporalResult, "v1");
        assertVertexRankEquals(normalResult, temporalResult, "v2");
        assertVertexRankEquals(normalResult, temporalResult, "v3");
        assertVertexRankEquals(normalResult, temporalResult, "v4");
    }

    private static TinkerGraph createTemporalGraph() {
        final TinkerGraph graph = TinkerGraph.open();

        final Vertex early = graph.addVertex(T.id, 1, T.label, "vertex", "name", "early");
        final Vertex late = graph.addVertex(T.id, 2, T.label, "vertex", "name", "late");
        final Vertex bridge = graph.addVertex(T.id, 3, T.label, "vertex", "name", "bridge");
        final Vertex target = graph.addVertex(T.id, 4, T.label, "vertex", "name", "target");

        early.addEdge("link", bridge, "startTime", "2024-01-01T00:00:00", "endTime", "2024-01-02T00:00:00");
        late.addEdge("link", bridge, "startTime", "2024-01-05T00:00:00", "endTime", "2024-01-06T00:00:00");
        bridge.addEdge("link", target, "startTime", "2024-01-03T00:00:00", "endTime", "2024-01-04T00:00:00");

        return graph;
    }

    private static TinkerGraph createSequentialGraph() {
        final TinkerGraph graph = TinkerGraph.open();

        final Vertex v1 = graph.addVertex(T.id, 11, T.label, "vertex", "name", "v1");
        final Vertex v2 = graph.addVertex(T.id, 12, T.label, "vertex", "name", "v2");
        final Vertex v3 = graph.addVertex(T.id, 13, T.label, "vertex", "name", "v3");
        final Vertex v4 = graph.addVertex(T.id, 14, T.label, "vertex", "name", "v4");

        v1.addEdge("link", v2, "startTime", "2024-01-01T00:00:00", "endTime", "2024-01-02T00:00:00");
        v2.addEdge("link", v3, "startTime", "2024-01-02T00:00:00", "endTime", "2024-01-03T00:00:00");
        v3.addEdge("link", v4, "startTime", "2024-01-03T00:00:00", "endTime", "2024-01-04T00:00:00");

        return graph;
    }

    private static void assertVertexRankEquals(final ComputerResult normalResult, final ComputerResult temporalResult,
                                               final String vertexName) {
        final double plainRank = normalResult.graph().traversal().V().has("name", vertexName).<Double>values("plainRank").next();
        final double temporalRank = temporalResult.graph().traversal().V().has("name", vertexName).<Double>values("temporalRank").next();
        assertEquals("Expected temporal PageRank to match normal PageRank for " + vertexName, plainRank, temporalRank, 0.0d);
    }
}
