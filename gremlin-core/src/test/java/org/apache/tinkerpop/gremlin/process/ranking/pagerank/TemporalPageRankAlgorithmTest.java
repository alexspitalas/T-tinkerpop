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

import org.junit.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class TemporalPageRankAlgorithmTest {

    private static final double TOLERANCE = 0.0000000001d;

    @Test
    public void shouldComputePaperAlgorithmRanksForChronologicalStream() {
        final TemporalPageRankAlgorithm algorithm = new TemporalPageRankAlgorithm(
                new TemporalPageRankAlgorithm.Config(0.85d, 0.5d, "startTime", "endTime", "rank", true));

        final Map<Object, Double> ranks = algorithm.execute(Arrays.asList(
                edge("A", "B", "e1", "2024-01-01T09:00:00Z", "2024-01-01T09:01:00Z"),
                edge("B", "C", "e2", "2024-01-01T09:05:00Z", "2024-01-01T09:06:00Z"),
                edge("C", "D", "e3", "2024-01-01T09:07:00Z", "2024-01-01T09:08:00Z"),
                edge("A", "D", "e4", "2024-01-01T09:10:00Z", "2024-01-01T09:11:00Z")));

        final double sum = 1.3051546875d;
        assertEquals(0.3000000000d / sum, ranks.get("A"), TOLERANCE);
        assertEquals(0.2775000000d / sum, ranks.get("B"), TOLERANCE);
        assertEquals(0.3316875000d / sum, ranks.get("C"), TOLERANCE);
        assertEquals(0.3959671875d / sum, ranks.get("D"), TOLERANCE);
    }

    @Test
    public void shouldBeSensitiveToLifetimeStartOrder() {
        final TemporalPageRankAlgorithm algorithm = new TemporalPageRankAlgorithm(
                new TemporalPageRankAlgorithm.Config(0.85d, 0.5d, "startTime", "endTime", "rank", true));

        final Map<Object, Double> first = algorithm.execute(Arrays.asList(
                edge("A", "B", "e1", "2024-01-01T09:00:00Z", "2024-01-01T09:01:00Z"),
                edge("B", "C", "e2", "2024-01-01T09:05:00Z", "2024-01-01T09:06:00Z")));
        final Map<Object, Double> second = algorithm.execute(Arrays.asList(
                edge("B", "C", "e1", "2024-01-01T09:00:00Z", "2024-01-01T09:01:00Z"),
                edge("A", "B", "e2", "2024-01-01T09:05:00Z", "2024-01-01T09:06:00Z")));

        assertNotEquals(first, second);
    }

    @Test
    public void shouldMoveMassWhenBetaEqualsOne() {
        final TemporalPageRankAlgorithm algorithm = new TemporalPageRankAlgorithm(
                new TemporalPageRankAlgorithm.Config(0.85d, 1.0d, "startTime", "endTime", "rank", false));

        final Map<Object, Double> ranks = algorithm.execute(Arrays.asList(
                edge("A", "B", "e1", "2024-01-01T09:00:00Z", "2024-01-01T09:01:00Z"),
                edge("B", "C", "e2", "2024-01-01T09:05:00Z", "2024-01-01T09:06:00Z")));

        assertEquals(0.15d, ranks.get("A"), TOLERANCE);
        assertEquals(0.2775d, ranks.get("B"), TOLERANCE);
        assertEquals(0.235875d, ranks.get("C"), TOLERANCE);
    }

    @Test
    public void shouldOnlyMoveMassAcrossSequentialLifetimes() {
        final TemporalPageRankAlgorithm algorithm = new TemporalPageRankAlgorithm(
                new TemporalPageRankAlgorithm.Config(0.85d, 0.5d, "startTime", "endTime", "rank", false));

        final Map<Object, Double> ranks = algorithm.execute(Arrays.asList(
                edge("A", "B", "e1", "2024-01-01T09:00:00Z", "2024-01-01T10:00:00Z"),
                edge("B", "C", "e2", "2024-01-01T09:05:00Z", "2024-01-01T11:00:00Z")));

        assertEquals(0.15d, ranks.get("A"), TOLERANCE);
        assertEquals(0.2775d, ranks.get("B"), TOLERANCE);
        assertEquals(0.1275d, ranks.get("C"), TOLERANCE);
    }

    @Test
    public void shouldRejectInvalidConfiguration() {
        final List<Runnable> invalidConfigs = Arrays.asList(
                () -> new TemporalPageRankAlgorithm.Config(0.0d, 0.5d, "startTime", "endTime", "rank", true),
                () -> new TemporalPageRankAlgorithm.Config(-0.1d, 0.5d, "startTime", "endTime", "rank", true),
                () -> new TemporalPageRankAlgorithm.Config(1.0d, 0.5d, "startTime", "endTime", "rank", true),
                () -> new TemporalPageRankAlgorithm.Config(1.1d, 0.5d, "startTime", "endTime", "rank", true),
                () -> new TemporalPageRankAlgorithm.Config(0.85d, 0.0d, "startTime", "endTime", "rank", true),
                () -> new TemporalPageRankAlgorithm.Config(0.85d, -0.1d, "startTime", "endTime", "rank", true),
                () -> new TemporalPageRankAlgorithm.Config(0.85d, 1.1d, "startTime", "endTime", "rank", true),
                () -> new TemporalPageRankAlgorithm.Config(0.85d, 0.5d, "", "endTime", "rank", true),
                () -> new TemporalPageRankAlgorithm.Config(0.85d, 0.5d, "startTime", "", "rank", true),
                () -> new TemporalPageRankAlgorithm.Config(0.85d, 0.5d, "startTime", "endTime", "", true));

        invalidConfigs.forEach(config -> assertThrows(IllegalArgumentException.class, config::run));
    }

    private static TemporalPageRankAlgorithm.TemporalEdge edge(final String outVertexId, final String inVertexId,
                                                               final String edgeId, final String startTime,
                                                               final String endTime) {
        return new TemporalPageRankAlgorithm.TemporalEdge(outVertexId, inVertexId, edgeId, Instant.parse(startTime),
                Instant.parse(endTime));
    }
}
