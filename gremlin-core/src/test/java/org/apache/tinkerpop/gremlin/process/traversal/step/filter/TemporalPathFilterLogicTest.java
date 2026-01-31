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
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyProperty;
import org.junit.Test;
import org.mockito.Mockito;

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
        when(edge.property("startTime")).thenReturn(startProp);

        if (end != null) {
            Property<Object> endProp = Mockito.mock(Property.class);
            when(endProp.isPresent()).thenReturn(true);
            when(endProp.value()).thenReturn(end);
            when(edge.property("endTime")).thenReturn(endProp);
        } else {
            when(edge.property("endTime")).thenReturn(EmptyProperty.instance());
        }
        
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
}