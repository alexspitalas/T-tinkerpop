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
package org.apache.tinkerpop.gremlin.process.ranking.pagerank;

import org.apache.tinkerpop.gremlin.process.traversal.step.map.TemporalPageRank;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedEdge;
import org.apache.tinkerpop.gremlin.util.CollectionUtil;
import org.apache.tinkerpop.gremlin.util.DatetimeHelper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource.traversal;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TemporalPageRankAlgorithmTest {

    private static final double TOLERANCE = 0.0000000001d;
    private static final double ALPHA = 0.85d;
    private static final String TRAVERSAL_RANK = "scopedTemporalRank";

    @Test
    public void shouldComputePaperAlgorithmRanksForChronologicalStream() {
        final Map<Object, Double> ranks = TemporalPageRankAlgorithm.execute(Arrays.asList(
                edge("A", "B", "e1", "2024-01-01T09:00:00Z", "2024-01-01T09:01:00Z"),
                edge("B", "C", "e2", "2024-01-01T09:05:00Z", "2024-01-01T09:06:00Z"),
                edge("C", "D", "e3", "2024-01-01T09:07:00Z", "2024-01-01T09:08:00Z"),
                edge("A", "D", "e4", "2024-01-01T09:10:00Z", "2024-01-01T09:11:00Z")),
                0.85d, 0.5d, true);

        final double sum = 1.3051546875d;
        assertEquals(0.3000000000d / sum, ranks.get("A"), TOLERANCE);
        assertEquals(0.2775000000d / sum, ranks.get("B"), TOLERANCE);
        assertEquals(0.3316875000d / sum, ranks.get("C"), TOLERANCE);
        assertEquals(0.3959671875d / sum, ranks.get("D"), TOLERANCE);
    }

    @Test
    public void shouldMoveMassWhenBetaEqualsOne() {
        final Map<Object, Double> ranks = TemporalPageRankAlgorithm.execute(Arrays.asList(
                edge("A", "B", "e1", "2024-01-01T09:00:00Z", "2024-01-01T09:01:00Z"),
                edge("B", "C", "e2", "2024-01-01T09:05:00Z", "2024-01-01T09:06:00Z")),
                0.85d, 1.0d, false);

        assertEquals(0.15d, ranks.get("A"), TOLERANCE);
        assertEquals(0.2775d, ranks.get("B"), TOLERANCE);
        assertEquals(0.235875d, ranks.get("C"), TOLERANCE);
    }

    @Test
    public void shouldMatchFlowPrOracleForTimestampStyleStream() {
        final List<TemporalPageRankAlgorithm.TemporalEdge> stream = timestampStyleStream();

        for (final double beta : Arrays.asList(0.5d, 1.0d)) {
            assertRanksEqual(flowPrOracle(stream, ALPHA, beta),
                    TemporalPageRankAlgorithm.execute(stream, ALPHA, beta, false));
        }
    }

    @Test
    public void shouldExposeBetaSensitivityInRetainedAndMovedMass() {
        final List<TemporalPageRankAlgorithm.TemporalEdge> stream = timestampStyleStream();

        final Map<Object, Double> betaPointOne = execute(stream, 0.1d, false);
        final Map<Object, Double> betaPointFive = execute(stream, 0.5d, false);
        final Map<Object, Double> betaPointNine = execute(stream, 0.9d, false);
        final Map<Object, Double> betaOne = execute(stream, 1.0d, false);

        assertEquals(0.5152875000d, betaPointOne.get("C"), TOLERANCE);
        assertEquals(0.5229375000d, betaPointFive.get("C"), TOLERANCE);
        assertEquals(0.5305875000d, betaPointNine.get("C"), TOLERANCE);
        assertTrue(betaPointOne.get("C") < betaPointFive.get("C"));
        assertTrue(betaPointFive.get("C") < betaPointNine.get("C"));

        assertEquals(0.8638687500d, betaOne.get("A"), TOLERANCE);
        assertTrue(betaOne.get("A") > betaPointNine.get("A"));
        assertTrue(betaOne.get("C") < betaPointNine.get("C"));
    }

    @Test
    public void shouldApproximateStaticPageRankAfterRepeatedWeightedScans() {
        final List<WeightedEdge> weightedGraph = Arrays.asList(
                weightedEdge("A", "B", 5),
                weightedEdge("B", "C", 5),
                weightedEdge("C", "A", 1));
        final Map<Object, Double> staticRanks = staticPageRank(weightedGraph, ALPHA);

        final Map<Object, Double> temporalRanks =
                TemporalPageRankAlgorithm.execute(repeatedStream(weightedGraph, 100), ALPHA, 1.0d, true);

        assertEquals(rankedVertices(staticRanks), rankedVertices(temporalRanks));
        assertEquals(staticRanks.get("A"), temporalRanks.get("A"), 0.002d);
        assertEquals(staticRanks.get("B"), temporalRanks.get("B"), 0.002d);
        assertEquals(staticRanks.get("C"), temporalRanks.get("C"), 0.002d);
    }

    @Test
    public void shouldAdaptTowardLaterDistributionAfterShift() {
        final List<WeightedEdge> initialPhase = Arrays.asList(
                weightedEdge("A", "B", 5),
                weightedEdge("B", "C", 5),
                weightedEdge("C", "A", 1));
        final List<WeightedEdge> laterPhase = Arrays.asList(
                weightedEdge("A", "C", 8),
                weightedEdge("B", "C", 8),
                weightedEdge("C", "A", 1));
        final Map<Object, Double> laterStaticRanks = staticPageRank(laterPhase, ALPHA);

        final Map<Object, Double> beforeShift = execute(repeatedStream(initialPhase, 80), 1.0d, true);
        final List<TemporalPageRankAlgorithm.TemporalEdge> shiftedStream = new ArrayList<>();
        shiftedStream.addAll(repeatedStream(initialPhase, 80, 0));
        shiftedStream.addAll(repeatedStream(laterPhase, 80, shiftedStream.size()));
        final Map<Object, Double> afterShift = execute(shiftedStream, 1.0d, true);

        assertEquals("A", rankedVertices(laterStaticRanks).get(0));
        assertTrue(afterShift.get("A") > beforeShift.get("A"));
        assertTrue(afterShift.get("B") < beforeShift.get("B"));
        assertTrue(l1Distance(afterShift, laterStaticRanks) < l1Distance(beforeShift, laterStaticRanks) * 0.5d);
    }

    @Test
    public void shouldOnlyMoveMassAcrossSequentialLifetimes() {
        final Map<Object, Double> ranks = TemporalPageRankAlgorithm.execute(Arrays.asList(
                edge("A", "B", "e1", "2024-01-01T09:00:00Z", "2024-01-01T10:00:00Z"),
                edge("B", "C", "e2", "2024-01-01T09:05:00Z", "2024-01-01T11:00:00Z")),
                0.85d, 0.5d, false);

        assertEquals(0.15d, ranks.get("A"), TOLERANCE);
        assertEquals(0.2775d, ranks.get("B"), TOLERANCE);
        assertEquals(0.1275d, ranks.get("C"), TOLERANCE);
    }

    @Test
    public void shouldReleaseMassWhenPriorLifetimeEndsAtNextStart() {
        final Map<Object, Double> ranks = TemporalPageRankAlgorithm.execute(Arrays.asList(
                edge("A", "B", "e1", "2024-01-01T09:00:00Z", "2024-01-01T10:00:00Z"),
                edge("B", "C", "e2", "2024-01-01T10:00:00Z", "2024-01-01T11:00:00Z")),
                0.85d, 0.5d, false);

        assertEquals(0.15d, ranks.get("A"), TOLERANCE);
        assertEquals(0.2775d, ranks.get("B"), TOLERANCE);
        assertEquals(0.1816875d, ranks.get("C"), TOLERANCE);
    }

    @Test
    public void shouldDefensivelyCopyTemporalEdgeDates() {
        final Date start = DatetimeHelper.parse("2024-01-01T09:00:00Z");
        final Date end = DatetimeHelper.parse("2024-01-01T10:00:00Z");
        final TemporalPageRankAlgorithm.TemporalEdge edge =
                new TemporalPageRankAlgorithm.TemporalEdge("A", "B", "e1", start, end);
        final Date expectedStart = new Date(start.getTime());
        final Date expectedEnd = new Date(end.getTime());

        start.setTime(end.getTime() + 1_000L);
        end.setTime(start.getTime() + 1_000L);

        assertEquals(expectedStart, edge.getStartTime());
        assertEquals(expectedEnd, edge.getEndTime());

        edge.getStartTime().setTime(expectedEnd.getTime());
        edge.getEndTime().setTime(expectedStart.getTime());

        assertEquals(expectedStart, edge.getStartTime());
        assertEquals(expectedEnd, edge.getEndTime());
    }

    @Test
    public void shouldRejectInvalidParameters() {
        final List<Runnable> invalidExecutions = Arrays.asList(
                () -> TemporalPageRankAlgorithm.execute(timestampStyleStream(), 0.0d, 0.5d, true),
                () -> TemporalPageRankAlgorithm.execute(timestampStyleStream(), -0.1d, 0.5d, true),
                () -> TemporalPageRankAlgorithm.execute(timestampStyleStream(), 1.0d, 0.5d, true),
                () -> TemporalPageRankAlgorithm.execute(timestampStyleStream(), 1.1d, 0.5d, true),
                () -> TemporalPageRankAlgorithm.execute(timestampStyleStream(), 0.85d, 0.0d, true),
                () -> TemporalPageRankAlgorithm.execute(timestampStyleStream(), 0.85d, -0.1d, true),
                () -> TemporalPageRankAlgorithm.execute(timestampStyleStream(), 0.85d, 1.1d, true));

        invalidExecutions.forEach(execution -> assertThrows(IllegalArgumentException.class, execution::run));
    }

    @Test
    public void shouldRespectIncomingVertexScopeInTraversalStep() {
        final Map<Object, Double> writtenRanks = new LinkedHashMap<>();
        final Vertex a = vertex("a", true, writtenRanks);
        final Vertex b = vertex("b", true, writtenRanks);
        final Vertex c = vertex("c", false, writtenRanks);
        final TemporalPageRankAlgorithm.TemporalEdge internalEdge = sequentialEdge("a", "b", "ab", 0);
        final TemporalPageRankAlgorithm.TemporalEdge crossingEdge = sequentialEdge("b", "c", "bc", 1);
        final TemporalPageRankAlgorithm.TemporalEdge incomingCrossingEdge = sequentialEdge("c", "a", "ca", 2);
        final Graph graph = mock(Graph.class);

        when(graph.vertices()).thenAnswer(invocation -> Arrays.asList(a, b, c).iterator());
        when(graph.vertices(eq("a"))).thenAnswer(invocation -> Arrays.asList(a).iterator());
        when(graph.vertices(eq("b"))).thenAnswer(invocation -> Arrays.asList(b).iterator());
        when(graph.edges()).thenAnswer(invocation -> Arrays.asList(
                detachedEdge(internalEdge), detachedEdge(crossingEdge), detachedEdge(incomingCrossingEdge)).iterator());

        final List<Vertex> result = traversal().withEmbedded(graph).V().has("selected", true).temporalPageRank().
                with(TemporalPageRank.propertyName, TRAVERSAL_RANK).
                toList();

        assertEquals(Arrays.asList(a, b), result);
        assertTrue(writtenRanks.containsKey("a"));
        assertTrue(writtenRanks.containsKey("b"));
        assertFalse(writtenRanks.containsKey("c"));

        final Map<Object, Double> scopedRanks = TemporalPageRankAlgorithm.execute(Arrays.asList(internalEdge),
                ALPHA, TemporalPageRankAlgorithm.DEFAULT_BETA, TemporalPageRankAlgorithm.DEFAULT_NORMALIZE);
        final Map<Object, Double> graphWideRanks = TemporalPageRankAlgorithm.execute(
                Arrays.asList(internalEdge, crossingEdge, incomingCrossingEdge),
                ALPHA, TemporalPageRankAlgorithm.DEFAULT_BETA, TemporalPageRankAlgorithm.DEFAULT_NORMALIZE);

        assertEquals(scopedRanks.get("a"), writtenRanks.get("a"), TOLERANCE);
        assertEquals(scopedRanks.get("b"), writtenRanks.get("b"), TOLERANCE);
        assertFalse(Math.abs(graphWideRanks.get("a") - writtenRanks.get("a")) < TOLERANCE);
        assertFalse(Math.abs(graphWideRanks.get("b") - writtenRanks.get("b")) < TOLERANCE);
    }

    private static Map<Object, Double> execute(final List<TemporalPageRankAlgorithm.TemporalEdge> stream,
                                               final double beta, final boolean normalize) {
        return TemporalPageRankAlgorithm.execute(stream, ALPHA, beta, normalize);
    }

    private static List<TemporalPageRankAlgorithm.TemporalEdge> timestampStyleStream() {
        return Arrays.asList(
                sequentialEdge("A", "B", "e1", 0),
                sequentialEdge("B", "C", "e2", 1),
                sequentialEdge("A", "C", "e3", 2),
                sequentialEdge("C", "A", "e4", 3),
                sequentialEdge("B", "A", "e5", 4));
    }

    private static Map<Object, Double> flowPrOracle(final List<TemporalPageRankAlgorithm.TemporalEdge> stream,
                                                   final double alpha, final double beta) {
        final List<TemporalPageRankAlgorithm.TemporalEdge> sortedStream = new ArrayList<>(stream);
        sortedStream.sort(TemporalPageRankAlgorithm.TemporalEdge.chronological());
        final Map<Object, Double> ranks = new LinkedHashMap<>();
        final Map<Object, Double> activeMass = new LinkedHashMap<>();
        final double injected = 1.0d - alpha;

        for (final TemporalPageRankAlgorithm.TemporalEdge edge : sortedStream) {
            final Object source = edge.getOutVertexId();
            final Object target = edge.getInVertexId();
            ranks.putIfAbsent(source, 0.0d);
            ranks.putIfAbsent(target, 0.0d);
            activeMass.putIfAbsent(source, 0.0d);
            activeMass.putIfAbsent(target, 0.0d);

            ranks.put(source, ranks.get(source) + injected);
            activeMass.put(source, activeMass.get(source) + injected);

            final double activeSourceMass = activeMass.get(source);
            ranks.put(target, ranks.get(target) + activeSourceMass * alpha);

            if (Double.compare(beta, 1.0d) == 0) {
                activeMass.put(target, activeMass.get(target) + activeSourceMass * alpha);
                activeMass.put(source, 0.0d);
            } else {
                activeMass.put(target, activeMass.get(target) + activeSourceMass * (1.0d - beta) * alpha);
                activeMass.put(source, activeSourceMass * beta);
            }
        }

        return ranks;
    }

    private static Map<Object, Double> staticPageRank(final List<WeightedEdge> edges, final double alpha) {
        final Map<Object, Double> outWeight = new LinkedHashMap<>();
        for (final WeightedEdge edge : edges) {
            outWeight.putIfAbsent(edge.outVertexId, 0.0d);
            outWeight.putIfAbsent(edge.inVertexId, 0.0d);
            outWeight.put(edge.outVertexId, outWeight.get(edge.outVertexId) + edge.weight);
        }

        final double totalOutWeight = outWeight.values().stream().mapToDouble(Double::doubleValue).sum();
        final Map<Object, Double> personalization = new LinkedHashMap<>();
        final Map<Object, Double> ranks = new LinkedHashMap<>();
        for (final Object vertexId : outWeight.keySet()) {
            personalization.put(vertexId, outWeight.get(vertexId) / totalOutWeight);
            ranks.put(vertexId, 1.0d / outWeight.size());
        }

        for (int iteration = 0; iteration < 1_000; iteration++) {
            final Map<Object, Double> nextRanks = new LinkedHashMap<>();
            for (final Object vertexId : outWeight.keySet()) {
                nextRanks.put(vertexId, (1.0d - alpha) * personalization.get(vertexId));
            }

            for (final Object source : outWeight.keySet()) {
                if (outWeight.get(source) == 0.0d) {
                    for (final Object target : outWeight.keySet()) {
                        nextRanks.put(target, nextRanks.get(target) + alpha * ranks.get(source) * personalization.get(target));
                    }
                    continue;
                }

                for (final WeightedEdge edge : edges) {
                    if (edge.outVertexId.equals(source)) {
                        nextRanks.put(edge.inVertexId, nextRanks.get(edge.inVertexId) +
                                alpha * ranks.get(source) * edge.weight / outWeight.get(source));
                    }
                }
            }

            final double maxDelta = outWeight.keySet().stream().
                    mapToDouble(vertexId -> Math.abs(nextRanks.get(vertexId) - ranks.get(vertexId))).
                    max().orElse(0.0d);
            ranks.clear();
            ranks.putAll(nextRanks);
            if (maxDelta < 0.000000000001d)
                break;
        }

        return ranks;
    }

    private static List<TemporalPageRankAlgorithm.TemporalEdge> repeatedStream(final List<WeightedEdge> edges,
                                                                               final int scans) {
        return repeatedStream(edges, scans, 0);
    }

    private static List<TemporalPageRankAlgorithm.TemporalEdge> repeatedStream(final List<WeightedEdge> edges,
                                                                               final int scans,
                                                                               final int offset) {
        final List<TemporalPageRankAlgorithm.TemporalEdge> stream = new ArrayList<>();
        int sequence = offset;
        for (int scan = 0; scan < scans; scan++) {
            for (final WeightedEdge edge : edges) {
                for (int i = 0; i < edge.weight; i++) {
                    stream.add(sequentialEdge(edge.outVertexId, edge.inVertexId, "e" + sequence, sequence));
                    sequence++;
                }
            }
        }
        return stream;
    }

    private static List<Object> rankedVertices(final Map<Object, Double> ranks) {
        return ranks.entrySet().stream().
                sorted(Map.Entry.<Object, Double>comparingByValue(Comparator.reverseOrder())).
                map(Map.Entry::getKey).
                collect(Collectors.toList());
    }

    private static double l1Distance(final Map<Object, Double> left, final Map<Object, Double> right) {
        return right.keySet().stream().mapToDouble(vertexId -> Math.abs(left.get(vertexId) - right.get(vertexId))).sum();
    }

    private static void assertRanksEqual(final Map<Object, Double> expected, final Map<Object, Double> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((vertexId, rank) -> assertEquals(rank, actual.get(vertexId), TOLERANCE));
    }

    private static TemporalPageRankAlgorithm.TemporalEdge sequentialEdge(final String outVertexId,
                                                                         final String inVertexId,
                                                                         final String edgeId,
                                                                         final int sequence) {
        final Date start = new Date(DatetimeHelper.parse("2024-01-01T00:00:00Z").getTime() + sequence * 2_000L);
        return new TemporalPageRankAlgorithm.TemporalEdge(outVertexId, inVertexId, edgeId,
                start, new Date(start.getTime() + 1_000L));
    }

    private static TemporalPageRankAlgorithm.TemporalEdge edge(final String outVertexId, final String inVertexId,
                                                               final String edgeId, final String startTime,
                                                               final String endTime) {
        return new TemporalPageRankAlgorithm.TemporalEdge(outVertexId, inVertexId, edgeId,
                DatetimeHelper.parse(startTime), DatetimeHelper.parse(endTime));
    }

    private static Vertex vertex(final String id, final boolean selected, final Map<Object, Double> writtenRanks) {
        final Vertex vertex = mock(Vertex.class);
        final VertexProperty<Boolean> selectedProperty = mock(VertexProperty.class);

        when(vertex.id()).thenReturn(id);
        when(selectedProperty.value()).thenReturn(selected);
        when(vertex.properties("selected")).thenAnswer(invocation -> Arrays.asList(selectedProperty).iterator());
        when(vertex.property(eq(VertexProperty.Cardinality.single), eq(TRAVERSAL_RANK), any())).
                thenAnswer(invocation -> {
                    writtenRanks.put(id, invocation.getArgument(2));
                    return mock(VertexProperty.class);
                });
        return vertex;
    }

    private static DetachedEdge detachedEdge(final TemporalPageRankAlgorithm.TemporalEdge edge) {
        return new DetachedEdge(edge.getEdgeId(), "temporal",
                CollectionUtil.asMap("startTime", edge.getStartTime(), "endTime", edge.getEndTime()),
                edge.getOutVertexId(), Vertex.DEFAULT_LABEL, edge.getInVertexId(), Vertex.DEFAULT_LABEL);
    }

    private static WeightedEdge weightedEdge(final String outVertexId, final String inVertexId, final int weight) {
        return new WeightedEdge(outVertexId, inVertexId, weight);
    }

    private static final class WeightedEdge {
        private final String outVertexId;
        private final String inVertexId;
        private final int weight;

        private WeightedEdge(final String outVertexId, final String inVertexId, final int weight) {
            this.outVertexId = outVertexId;
            this.inVertexId = inVertexId;
            this.weight = weight;
        }
    }
}
