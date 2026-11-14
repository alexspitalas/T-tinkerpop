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
package org.apache.tinkerpop.gremlin.process.traversal.step.filter;

import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyProperty;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests for TemporalPathFilterStep using mocked Edge objects.
 * Tests the three temporal path types: CONTINUOUS, SEQUENTIAL, and PAIRWISE_CONTINUOUS
 * 
 * These tests verify the filtering logic without requiring a full graph database.
 * 
 * @author Alex Spitalas
 */
public class TemporalPathFilterLogicTest {

    /**
     * Creates a mock Edge with startTime and endTime properties.
     * If end is null, endTime is treated as unbounded (LocalDateTime.MAX)
     */
    private Edge mockEdge(String start, String end) {
        Edge edge = Mockito.mock(Edge.class);
        
        Property<Object> startProp = Mockito.mock(Property.class);
        when(startProp.isPresent()).thenReturn(true);
        when(startProp.value()).thenReturn(start);
        when(startProp.orElse(null)).thenReturn(start);
        when(edge.property(Lifetime.START_TIME)).thenReturn(startProp);

        if (end != null) {
            Property<Object> endProp = Mockito.mock(Property.class);
            when(endProp.isPresent()).thenReturn(true);
            when(endProp.value()).thenReturn(end);
            when(endProp.orElse(null)).thenReturn(end);
            when(edge.property(Lifetime.END_TIME)).thenReturn(endProp);
        } else {
            when(edge.property(Lifetime.END_TIME)).thenReturn(EmptyProperty.instance());
        }
        
        return edge;
    }

    private Edge mockMultiIntervalEdge(long... startEndPairs) {
        final List<Lifetime.Interval> intervals = new ArrayList<>();
        for (int i = 0; i < startEndPairs.length; i += 2) {
            intervals.add(new Lifetime.Interval(startEndPairs[i], startEndPairs[i + 1]));
        }
        final String serialized = Lifetime.from(intervals).toTemporalIntervalsString();
        final Edge edge = Mockito.mock(Edge.class);
        final Property<Object> ivProp = Mockito.mock(Property.class);
        when(ivProp.isPresent()).thenReturn(true);
        when(ivProp.value()).thenReturn(serialized);
        when(ivProp.orElse(null)).thenReturn(serialized);
        when(edge.property(Lifetime.TEMPORAL_INTERVALS)).thenReturn(ivProp);
        return edge;
    }

    /**
     * Creates a mock Traverser with the given current edge and path.
     */
    private Traverser.Admin mockTraverser(Edge currentEdge, Path path) {
        Traverser.Admin traverser = Mockito.mock(Traverser.Admin.class);
        when(traverser.get()).thenReturn(currentEdge);
        when(traverser.path()).thenReturn(path);
        return traverser;
    }

    /**
     * Creates a mock Path with the given elements.
     */
    private Path mockPath(Object... elements) {
        Path path = Mockito.mock(Path.class);
        when(path.size()).thenReturn(elements.length);
        for (int i = 0; i < elements.length; i++) {
            when(path.get(i)).thenReturn(elements[i]);
        }
        return path;
    }

    /**
     * Creates a mock Traversal.Admin for step instantiation.
     */
    private Traversal.Admin mockTraversal() {
        return __.start().asAdmin();
    }

    /**
     * TEST: CONTINUOUS PATH - All edges must overlap
     * Three edges all overlapping at their intersection
     */
    @Test
    public void shouldFilterContinuousPath() {
        TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(), 
            TemporalPathFilterStep.TemporalPathType.CONTINUOUS
        );

        // Edges with overlapping intervals
        Edge e1 = mockEdge("2023-01-01T10:00:00", "2023-01-01T12:00:00");
        Edge e2 = mockEdge("2023-01-01T11:00:00", "2023-01-01T13:00:00");
        Edge e3 = mockEdge("2023-01-01T11:30:00", "2023-01-01T12:30:00");

        // All three overlap at [11:30, 12:00] - PASS
        Path path = mockPath(e1, e2, e3);
        assertTrue("All edges should overlap", step.filter(mockTraverser(e3, path)));

        // e4 does NOT overlap with the intersection of e1, e2
        // Intersection of e1,e2 is [11:00, 12:00]
        // e4 is [12:30, 13:30] - no overlap
        Edge e4 = mockEdge("2023-01-01T12:30:00", "2023-01-01T13:30:00");
        Path pathFail = mockPath(e1, e2, e4);
        assertFalse("Edge outside intersection should fail", step.filter(mockTraverser(e4, pathFail)));
    }

    /**
     * TEST: SEQUENTIAL PATH - Edges must be in temporal order
     * Edge N must start at or after Edge N-1 ends
     */
    @Test
    public void shouldFilterSequentialPath() {
        TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(), 
            TemporalPathFilterStep.TemporalPathType.SEQUENTIAL
        );

        // e2 starts exactly when e1 ends - PASS
        Edge e1 = mockEdge("2023-01-01T10:00:00", "2023-01-01T11:00:00");
        Edge e2 = mockEdge("2023-01-01T11:00:00", "2023-01-01T12:00:00");
        Path path = mockPath(e1, e2);
        assertTrue("Sequential edges should pass", step.filter(mockTraverser(e2, path)));

        // e3 starts before e2 ends - FAIL
        Edge e3 = mockEdge("2023-01-01T11:30:00", "2023-01-01T12:30:00");
        Path pathFail = mockPath(e2, e3);
        assertFalse("Overlapping edges should fail sequential", step.filter(mockTraverser(e3, pathFail)));
        
        // e4 starts after e2 ends (gap allowed) - PASS
        Edge e4 = mockEdge("2023-01-01T12:30:00", "2023-01-01T13:00:00");
        Path pathWait = mockPath(e2, e4);
        assertTrue("Edges with gap should pass", step.filter(mockTraverser(e4, pathWait)));
    }

    /**
     * TEST: PAIRWISE-CONTINUOUS PATH - Consecutive edges must overlap
     * Only checks current edge against the immediate previous edge
     */
    @Test
    public void shouldFilterPairwiseContinuousPath() {
        TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(), 
            TemporalPathFilterStep.TemporalPathType.PAIRWISE_CONTINUOUS
        );

        Edge e1 = mockEdge("2023-01-01T10:00:00", "2023-01-01T12:00:00");
        Edge e2 = mockEdge("2023-01-01T11:00:00", "2023-01-01T13:00:00");
        Edge e3 = mockEdge("2023-01-01T12:30:00", "2023-01-01T14:00:00");

        // e1 and e2 overlap [11:00, 12:00] - PASS
        Path path12 = mockPath(e1, e2);
        assertTrue("Overlapping pair should pass", step.filter(mockTraverser(e2, path12)));

        // e2 and e3 overlap [12:30, 13:00] - PASS
        Path path23 = mockPath(e2, e3);
        assertTrue("Overlapping pair should pass", step.filter(mockTraverser(e3, path23)));

        // In path [e1, e2, e3]: e1 and e3 DON'T overlap, but pairwise only checks e2-e3 - PASS
        Path path123 = mockPath(e1, e2, e3);
        assertTrue("Pairwise only checks consecutive pair", step.filter(mockTraverser(e3, path123)));

        // e4 does NOT overlap with e3 - FAIL
        Edge e4 = mockEdge("2023-01-01T14:30:00", "2023-01-01T15:00:00");
        Path pathFail = mockPath(e3, e4);
        assertFalse("Non-overlapping pair should fail", step.filter(mockTraverser(e4, pathFail)));
    }

    /**
     * TEST: Single edge always passes (no predecessors to check)
     */
    @Test
    public void shouldFilterContinuousPathWithSingleEdge() {
        TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(), 
            TemporalPathFilterStep.TemporalPathType.CONTINUOUS
        );
        Edge e1 = mockEdge("2023-01-01T10:00:00", "2023-01-01T12:00:00");
        Path path = mockPath(e1);
        assertTrue("Single edge should always pass", step.filter(mockTraverser(e1, path)));
    }

    /**
     * TEST: Unbounded end time (no endTime property)
     * Should be treated as LocalDateTime.MAX
     */
    @Test
    public void shouldFilterContinuousPathWithInfiniteEnd() {
        TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(), 
            TemporalPathFilterStep.TemporalPathType.CONTINUOUS
        );
        Edge e1 = mockEdge("2023-01-01T10:00:00", null);  // No end time
        Edge e2 = mockEdge("2023-01-01T11:00:00", "2023-01-01T13:00:00");
        Path path = mockPath(e1, e2);
        assertTrue("Unbounded edges should overlap", step.filter(mockTraverser(e2, path)));
    }

    /**
     * TEST: Transitive failure in continuous path
     * e1 and e2 overlap, e2 and e3 overlap, but all three don't have common intersection
     */
    @Test
    public void shouldFilterContinuousPathWithTransitiveFailure() {
        TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(), 
            TemporalPathFilterStep.TemporalPathType.CONTINUOUS
        );
        Edge e1 = mockEdge("2023-01-01T10:00:00", "2023-01-01T12:00:00");
        Edge e2 = mockEdge("2023-01-01T11:00:00", "2023-01-01T13:00:00");
        Edge e3 = mockEdge("2023-01-01T12:30:00", "2023-01-01T14:00:00");

        // e1 and e2 overlap [11:00, 12:00]
        // e2 and e3 overlap [12:30, 13:00]
        // BUT [11:00, 12:00] and [12:30, 13:00] are disjoint - FAIL
        Path path = mockPath(e1, e2, e3);
        assertFalse("No common intersection should fail", step.filter(mockTraverser(e3, path)));
    }

    /**
     * TEST: Sequential path with exact temporal boundaries
     */
    @Test
    public void shouldFilterSequentialPathWithExactMatch() {
        TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(), 
            TemporalPathFilterStep.TemporalPathType.SEQUENTIAL
        );
        Edge e1 = mockEdge("2023-01-01T10:00:00", "2023-01-01T11:00:00");
        Edge e2 = mockEdge("2023-01-01T11:00:00", "2023-01-01T12:00:00");
        Path path = mockPath(e1, e2);
        assertTrue("Exact temporal boundary should pass", step.filter(mockTraverser(e2, path)));
    }

    /**
     * TEST: Sequential path with gap between edges
     */
    @Test
    public void shouldFilterSequentialPathWithGap() {
        TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(), 
            TemporalPathFilterStep.TemporalPathType.SEQUENTIAL
        );
        Edge e1 = mockEdge("2023-01-01T10:00:00", "2023-01-01T11:00:00");
        Edge e2 = mockEdge("2023-01-01T12:00:00", "2023-01-01T13:00:00");  // 1 hour gap
        Path path = mockPath(e1, e2);
        assertTrue("Gap between edges is allowed in sequential", step.filter(mockTraverser(e2, path)));
    }

    /**
     * TEST: Pairwise-Continuous allows transitive non-overlap
     * Path [e1, e2, e3] where e1-e2 overlap, e2-e3 overlap, but e1-e3 don't overlap
     * This is valid for pairwise-continuous (only checks consecutive pairs)
     */
    @Test
    public void shouldFilterPairwiseContinuousPathWithTransitiveOverlap() {
        TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(), 
            TemporalPathFilterStep.TemporalPathType.PAIRWISE_CONTINUOUS
        );
        Edge e1 = mockEdge("2023-01-01T10:00:00", "2023-01-01T12:00:00");
        Edge e2 = mockEdge("2023-01-01T11:00:00", "2023-01-01T13:00:00");
        Edge e3 = mockEdge("2023-01-01T12:30:00", "2023-01-01T14:00:00");

        // Path [e1, e2, e3]:
        // e1-e2 overlap [11:00, 12:00] ✓
        // e2-e3 overlap [12:30, 13:00] ✓
        // e1-e3 are disjoint, but that's OK - pairwise only checks consecutive pairs
        Path path = mockPath(e1, e2, e3);
        assertTrue("Pairwise allows transitive non-overlap", step.filter(mockTraverser(e3, path)));
    }

    @Test
    public void shouldFilterSequentialPathWithMinDelay() {
        // Min delay 1 hour (3600000 ms)
        TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(), 
            TemporalPathFilterStep.TemporalPathType.SEQUENTIAL,
            3600000, Long.MAX_VALUE, false
        );

        Edge e1 = mockEdge("2023-01-01T10:00:00", "2023-01-01T11:00:00");
        
        // e2 starts 30 mins after e1 ends - FAIL (gap < min)
        Edge e2 = mockEdge("2023-01-01T11:30:00", "2023-01-01T12:30:00");
        Path pathFail = mockPath(e1, e2);
        assertFalse("Gap smaller than minDelay should fail", step.filter(mockTraverser(e2, pathFail)));

        // e3 starts 1 hour after e1 ends - PASS (gap == min)
        Edge e3 = mockEdge("2023-01-01T12:00:00", "2023-01-01T13:00:00");
        Path pathPass = mockPath(e1, e3);
        assertTrue("Gap equal to minDelay should pass", step.filter(mockTraverser(e3, pathPass)));

        // e4 starts 2 hours after e1 ends - PASS (gap > min)
        Edge e4 = mockEdge("2023-01-01T13:00:00", "2023-01-01T14:00:00");
        Path pathPass2 = mockPath(e1, e4);
        assertTrue("Gap larger than minDelay should pass", step.filter(mockTraverser(e4, pathPass2)));
    }

    @Test
    public void shouldFilterSequentialPathWithMaxDelay() {
        // Max delay 2 hours (7200000 ms)
        TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(), 
            TemporalPathFilterStep.TemporalPathType.SEQUENTIAL,
            0, 7200000, false
        );

        Edge e1 = mockEdge("2023-01-01T10:00:00", "2023-01-01T11:00:00");

        // e2 starts 1 hour after e1 ends - PASS (gap < max)
        Edge e2 = mockEdge("2023-01-01T12:00:00", "2023-01-01T13:00:00");
        Path pathPass = mockPath(e1, e2);
        assertTrue("Gap smaller than maxDelay should pass", step.filter(mockTraverser(e2, pathPass)));

        // e3 starts 3 hours after e1 ends - FAIL (gap > max)
        Edge e3 = mockEdge("2023-01-01T14:00:00", "2023-01-01T15:00:00");
        Path pathFail = mockPath(e1, e3);
        assertFalse("Gap larger than maxDelay should fail", step.filter(mockTraverser(e3, pathFail)));
    }

    @Test
    public void shouldFilterPairwiseContinuousPathMonotone() {
        TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(), 
            TemporalPathFilterStep.TemporalPathType.PAIRWISE_CONTINUOUS,
            0, Long.MAX_VALUE, true // Monotone = true
        );

        Edge e1 = mockEdge("2023-01-01T10:00:00", "2023-01-01T12:00:00");
        
        // e2 starts at 11:00 (after e1 start) and overlaps - PASS
        Edge e2 = mockEdge("2023-01-01T11:00:00", "2023-01-01T13:00:00");
        Path pathPass = mockPath(e1, e2);
        assertTrue("Monotone increasing start time should pass", step.filter(mockTraverser(e2, pathPass)));

        // e3 starts at 09:00 (before e1 start) and overlaps - FAIL
        Edge e3 = mockEdge("2023-01-01T09:00:00", "2023-01-01T11:00:00");
        Path pathFail = mockPath(e1, e3);
        assertFalse("Decreasing start time should fail in monotone mode", step.filter(mockTraverser(e3, pathFail)));
    }

    @Test
    public void shouldFilterPairwiseContinuousPathNonMonotone() {
        TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(), 
            TemporalPathFilterStep.TemporalPathType.PAIRWISE_CONTINUOUS,
            0, Long.MAX_VALUE, false // Monotone = false
        );

        Edge e1 = mockEdge("2023-01-01T10:00:00", "2023-01-01T12:00:00");
        
        // e3 starts at 09:00 (before e1 start) and overlaps - PASS because monotone is false
        Edge e3 = mockEdge("2023-01-01T09:00:00", "2023-01-01T11:00:00");
        Path pathPass = mockPath(e1, e3);
        assertTrue("Decreasing start time should pass in non-monotone mode", step.filter(mockTraverser(e3, pathPass)));
    }

    @Test
    public void shouldFilterMultiIntervalSequentialPathWithUnboundedMaxDelay() {
        final TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(),
            TemporalPathFilterStep.TemporalPathType.SEQUENTIAL,
            10L, Long.MAX_VALUE, false
        );

        // e1: [10, 20), [500, 600)
        final Edge e1 = mockMultiIntervalEdge(10, 20, 500, 600);
        // e2: [25, 28) (violates minDelay 10 from 20), [30, 40) (valid: delay=10), [100, 110)
        final Edge e2 = mockMultiIntervalEdge(25, 28, 30, 40, 100, 110);
        // e3: [50, 60) -> delay from e2's [30, 40) is 10 >= 10 -> PASS
        final Edge e3Valid = mockMultiIntervalEdge(50, 60);
        assertTrue(step.filter(mockTraverser(e3Valid, mockPath(e1, e2, e3Valid))));

        // e3Early: [49, 60) -> delay from e2's earliest reachable [30, 40) is 9 < 10 -> FAIL
        final Edge e3TooEarly = mockMultiIntervalEdge(49, 60);
        assertFalse(step.filter(mockTraverser(e3TooEarly, mockPath(e1, e2, e3TooEarly))));

        // Unreachable early interval on e2 should not unlock e3:
        // eA=[100, 200), eB={[50, 80), [250, 300)}, eC=[120, 150)
        final Edge eA = mockMultiIntervalEdge(100, 200);
        final Edge eB = mockMultiIntervalEdge(50, 80, 250, 300);
        final Edge eC = mockMultiIntervalEdge(120, 150);
        assertFalse(step.filter(mockTraverser(eC, mockPath(eA, eB, eC))));
    }

    @Test
    public void shouldRetainAllReachableIntervalsWhenMaxDelayIsBounded() {
        // minDelay = 0, maxDelay = 50
        final TemporalPathFilterStep<Edge> step = new TemporalPathFilterStep<>(
            mockTraversal(),
            TemporalPathFilterStep.TemporalPathType.SEQUENTIAL,
            0L, 50L, false
        );

        // e1: [100, 200)
        // e2: {[210, 230), [240, 260)} -> both reachable from e1 (delays 10 and 40 <= 50)
        // e3: [290, 310) -> from first interval [210, 230) delay=60 (>50), from second [240, 260) delay=30 (<=50)
        final Edge e1 = mockMultiIntervalEdge(100, 200);
        final Edge e2 = mockMultiIntervalEdge(210, 230, 240, 260);
        final Edge e3 = mockMultiIntervalEdge(290, 310);
        assertTrue("Later reachable interval on e2 should satisfy maxDelay to e3",
            step.filter(mockTraverser(e3, mockPath(e1, e2, e3))));

        // Ensure intervals on e2 that exceed maxDelay from e1 are NOT carried to e3:
        // e1=[100, 120), e2={[125, 130), [200, 210)}, e3=[220, 230), maxDelay=30
        final TemporalPathFilterStep<Edge> tightStep = new TemporalPathFilterStep<>(
            mockTraversal(),
            TemporalPathFilterStep.TemporalPathType.SEQUENTIAL,
            0L, 30L, false
        );
        final Edge t1 = mockMultiIntervalEdge(100, 120);
        final Edge t2 = mockMultiIntervalEdge(125, 130, 200, 210); // [200, 210) is delay 80 > 30
        final Edge t3 = mockMultiIntervalEdge(220, 230);            // delay 90 > 30 from [125, 130)
        assertFalse("Unreachable later interval on e2 must not validate e3",
            tightStep.filter(mockTraverser(t3, mockPath(t1, t2, t3))));

        // Ensure later reachable interval on e1 can satisfy maxDelay to e2:
        final Edge m1 = mockMultiIntervalEdge(100, 120, 180, 200);
        final Edge m2 = mockMultiIntervalEdge(210, 220);
        assertTrue(tightStep.filter(mockTraverser(m2, mockPath(m1, m2))));
    }

    @Test
    public void shouldHandleSequentialPointIntervalsAndExtremeTimestampBoundaries() {
        final TemporalPathFilterStep<Edge> unboundedStep = new TemporalPathFilterStep<>(
            mockTraversal(),
            TemporalPathFilterStep.TemporalPathType.SEQUENTIAL
        );

        // Point intervals meeting half-open and point intervals
        final Edge p1 = mockMultiIntervalEdge(100, 100);
        final Edge p2 = mockMultiIntervalEdge(100, 200);
        final Edge p3 = mockMultiIntervalEdge(200, 200);
        assertTrue(unboundedStep.filter(mockTraverser(p3, mockPath(p1, p2, p3))));

        // Overflow safety: prevEnd = Long.MAX_VALUE with positive minDelay must fail cleanly
        final TemporalPathFilterStep<Edge> minDelayStep = new TemporalPathFilterStep<>(
            mockTraversal(),
            TemporalPathFilterStep.TemporalPathType.SEQUENTIAL,
            10L, Long.MAX_VALUE, false
        );
        final Edge openEnded = mockMultiIntervalEdge(100, Long.MAX_VALUE);
        final Edge atMax = mockMultiIntervalEdge(Long.MAX_VALUE, Long.MAX_VALUE);
        assertFalse(minDelayStep.filter(mockTraverser(atMax, mockPath(openEnded, atMax))));

        // Overflow safety in bounded delay with negative start/end and Long.MAX_VALUE
        final TemporalPathFilterStep<Edge> boundedStep = new TemporalPathFilterStep<>(
            mockTraversal(),
            TemporalPathFilterStep.TemporalPathType.SEQUENTIAL,
            0L, 100L, false
        );
        final Edge negToMax = mockMultiIntervalEdge(-50, -10, Long.MAX_VALUE - 50, Long.MAX_VALUE - 20);
        assertTrue(boundedStep.filter(mockTraverser(atMax, mockPath(negToMax, atMax))));
        final Edge onlyNeg = mockMultiIntervalEdge(-50, -10);
        assertFalse(boundedStep.filter(mockTraverser(atMax, mockPath(onlyNeg, atMax))));

        // Impossible delay bounds (minDelay > maxDelay) allow single edge, reject 2+ edges
        final TemporalPathFilterStep<Edge> impossibleStep = new TemporalPathFilterStep<>(
            mockTraversal(),
            TemporalPathFilterStep.TemporalPathType.SEQUENTIAL,
            100L, 50L, false
        );
        assertTrue(impossibleStep.filter(mockTraverser(p1, mockPath(p1))));
        assertFalse(impossibleStep.filter(mockTraverser(p2, mockPath(p1, p2))));
    }

    @Test
    public void shouldFilterMultiIntervalPairwiseContinuousPath() {
        final TemporalPathFilterStep<Edge> monotoneStep = new TemporalPathFilterStep<>(
            mockTraversal(),
            TemporalPathFilterStep.TemporalPathType.PAIRWISE_CONTINUOUS,
            0L, Long.MAX_VALUE, true
        );
        final Edge e1 = mockMultiIntervalEdge(100, 200, 500, 600);
        // [50, 150) intersects [100, 200) but violates monotone (50 < 100); [550, 650) intersects [500, 600) and 550 >= 500
        final Edge e2 = mockMultiIntervalEdge(50, 150, 550, 650);
        final Edge e3Valid = mockMultiIntervalEdge(580, 700);
        final Edge e3Invalid = mockMultiIntervalEdge(120, 180);

        assertTrue(monotoneStep.filter(mockTraverser(e3Valid, mockPath(e1, e2, e3Valid))));
        assertFalse(monotoneStep.filter(mockTraverser(e3Invalid, mockPath(e1, e2, e3Invalid))));
    }

    @Test
    public void shouldFilterMultiIntervalContinuousPath() {
        final TemporalPathFilterStep<Edge> continuousStep = new TemporalPathFilterStep<>(
            mockTraversal(),
            TemporalPathFilterStep.TemporalPathType.CONTINUOUS
        );

        // e1 and e2 share two disjoint intersection windows: [150, 200) and [550, 600)
        final Edge e1 = mockMultiIntervalEdge(100, 200, 500, 600);
        final Edge e2 = mockMultiIntervalEdge(150, 250, 550, 650);
        // e3Valid overlaps the second shared window [550, 600) at [580, 600)
        final Edge e3Valid = mockMultiIntervalEdge(300, 400, 580, 700);
        // e3Invalid sits in the gap [200, 550) and misses both shared windows
        final Edge e3Invalid = mockMultiIntervalEdge(210, 240, 510, 540);

        assertTrue(continuousStep.filter(mockTraverser(e3Valid, mockPath(e1, e2, e3Valid))));
        assertFalse(continuousStep.filter(mockTraverser(e3Invalid, mockPath(e1, e2, e3Invalid))));

        // Closed-right boundary via half-open + point interval: {[100, 200), [200, 200)} intersected across 3 hops at [200, 200)
        final Edge p1 = mockMultiIntervalEdge(100, 200, 200, 200);
        final Edge p2 = mockMultiIntervalEdge(200, 200, 300, 400);
        final Edge p3 = mockMultiIntervalEdge(200, 200);
        assertTrue(continuousStep.filter(mockTraverser(p3, mockPath(p1, p2, p3))));
    }
}
