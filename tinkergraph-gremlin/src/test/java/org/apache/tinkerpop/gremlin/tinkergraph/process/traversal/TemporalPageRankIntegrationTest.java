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
import org.apache.tinkerpop.gremlin.process.traversal.step.map.TemporalPageRank;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TemporalPageRankIntegrationTest {

    private static final double TOLERANCE = 0.0000000001d;

    @Test
    public void shouldWriteConfiguredNormalizedTemporalPageRankProperty() {
        final TinkerGraph graph = TinkerGraph.open();
        final GraphTraversalSource g = graph.traversal();
        final Vertex a = graph.addVertex(T.id, "A");
        final Vertex b = graph.addVertex(T.id, "B");
        final Vertex c = graph.addVertex(T.id, "C");
        final Vertex d = graph.addVertex(T.id, "D");
        final Vertex e = graph.addVertex(T.id, "E");
        a.addEdge("link", b, T.id, "e1",
                "eventStart", "2024-01-01T09:00:00", "eventEnd", "2024-01-01T09:01:00");
        b.addEdge("link", c, T.id, "e2",
                "eventStart", "2024-01-01T09:05:00", "eventEnd", "2024-01-01T09:06:00");
        c.addEdge("link", d, T.id, "e3",
                "eventStart", "2024-01-01T09:07:00", "eventEnd", "2024-01-01T09:08:00");
        a.addEdge("link", d, T.id, "e4",
                "eventStart", "2024-01-01T09:10:00", "eventEnd", "2024-01-01T09:11:00");

        g.V().temporalPageRank().
                with(TemporalPageRank.alpha, 0.85d).
                with(TemporalPageRank.beta, 0.5d).
                with(TemporalPageRank.startTimeProperty, "eventStart").
                with(TemporalPageRank.endTimeProperty, "eventEnd").
                with(TemporalPageRank.propertyName, "temporalRank").
                iterate();

        final double sum = 1.3051546875d;
        assertEquals(0.3000000000d / sum, rank(a, "temporalRank"), TOLERANCE);
        assertEquals(0.2775000000d / sum, rank(b, "temporalRank"), TOLERANCE);
        assertEquals(0.3316875000d / sum, rank(c, "temporalRank"), TOLERANCE);
        assertEquals(0.3959671875d / sum, rank(d, "temporalRank"), TOLERANCE);
        assertEquals(0.0d, rank(e, "temporalRank"), TOLERANCE);
    }

    @Test
    public void shouldComputeAndWriteRanksGraphWideWhenTraversalStartsFromSubset() {
        final TinkerGraph graph = TinkerGraph.open();
        final GraphTraversalSource g = graph.traversal();
        final Vertex a = graph.addVertex(T.id, "A");
        final Vertex b = graph.addVertex(T.id, "B");
        final Vertex c = graph.addVertex(T.id, "C");
        final Vertex d = graph.addVertex(T.id, "D");
        a.addEdge("link", b, T.id, "e1",
                "startTime", "2024-01-01T09:00:00", "endTime", "2024-01-01T09:01:00");
        b.addEdge("link", c, T.id, "e2",
                "startTime", "2024-01-01T09:05:00", "endTime", "2024-01-01T09:06:00");
        c.addEdge("link", d, T.id, "e3",
                "startTime", "2024-01-01T09:07:00", "endTime", "2024-01-01T09:08:00");

        final List<Vertex> starts = g.V("A", "B").temporalPageRank().
                with(TemporalPageRank.alpha, 0.85d).
                with(TemporalPageRank.beta, 0.5d).
                with(TemporalPageRank.normalize, false).
                with(TemporalPageRank.propertyName, "rank").
                toList();

        assertEquals(2, starts.size());
        assertTrue(starts.stream().anyMatch(vertex -> vertex.id().equals("A")));
        assertTrue(starts.stream().anyMatch(vertex -> vertex.id().equals("B")));
        assertEquals(0.15d, rank(a, "rank"), TOLERANCE);
        assertEquals(0.2775d, rank(b, "rank"), TOLERANCE);
        assertEquals(0.3316875d, rank(c, "rank"), TOLERANCE);
        assertEquals(0.2047171875d, rank(d, "rank"), TOLERANCE);
    }

    @Test
    public void shouldFailClearlyWhenStartTimePropertyIsMissing() {
        final TinkerGraph graph = TinkerGraph.open();
        final GraphTraversalSource g = graph.traversal();
        final Vertex a = graph.addVertex(T.id, "A");
        final Vertex b = graph.addVertex(T.id, "B");
        a.addEdge("link", b, T.id, "e1");

        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> g.V().temporalPageRank().iterate());

        assertTrue(exception.getMessage().contains("missing start time property"));
        assertTrue(exception.getMessage().contains("startTime"));
    }

    @Test
    public void shouldFailClearlyWhenEndTimePropertyIsMissing() {
        final TinkerGraph graph = TinkerGraph.open();
        final GraphTraversalSource g = graph.traversal();
        final Vertex a = graph.addVertex(T.id, "A");
        final Vertex b = graph.addVertex(T.id, "B");
        a.addEdge("link", b, T.id, "e1", "startTime", "2024-01-01T09:00:00");

        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> g.V().temporalPageRank().iterate());

        assertTrue(exception.getMessage().contains("missing end time property"));
        assertTrue(exception.getMessage().contains("endTime"));
    }

    @Test
    public void shouldFailClearlyWhenLifetimePropertyCannotBeParsed() {
        final TinkerGraph graph = TinkerGraph.open();
        final GraphTraversalSource g = graph.traversal();
        final Vertex a = graph.addVertex(T.id, "A");
        final Vertex b = graph.addVertex(T.id, "B");
        a.addEdge("link", b, T.id, "e1", "startTime", "not-a-date", "endTime", "2024-01-01T10:00:00");

        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> g.V().temporalPageRank().iterate());

        assertTrue(exception.getMessage().contains("unparseable 'startTime' value"));
        assertTrue(exception.getMessage().contains("not-a-date"));
    }

    @Test
    public void shouldOrderEqualLifetimeStartsByEdgeIdString() {
        final TinkerGraph graph = TinkerGraph.open();
        final GraphTraversalSource g = graph.traversal();
        final Vertex a = graph.addVertex(T.id, "A");
        final Vertex b = graph.addVertex(T.id, "B");
        final Vertex c = graph.addVertex(T.id, "C");
        b.addEdge("link", c, T.id, "2",
                "startTime", "2024-01-01T09:00:00", "endTime", "2024-01-01T10:00:00");
        a.addEdge("link", b, T.id, "1",
                "startTime", "2024-01-01T09:00:00", "endTime", "2024-01-01T10:00:00");

        g.V().temporalPageRank().
                with(TemporalPageRank.alpha, 0.85d).
                with(TemporalPageRank.beta, 0.5d).
                with(TemporalPageRank.normalize, false).
                with(TemporalPageRank.propertyName, "rank").
                iterate();

        assertEquals(0.15d, rank(a, "rank"), TOLERANCE);
        assertEquals(0.2775d, rank(b, "rank"), TOLERANCE);
        assertEquals(0.1275d, rank(c, "rank"), TOLERANCE);
    }

    private static double rank(final Vertex vertex, final String propertyName) {
        return ((Number) vertex.value(propertyName)).doubleValue();
    }
}
