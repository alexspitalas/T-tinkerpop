// TemporalPathIntegrationTest.java
// Using clean DSL syntax instead of manual step construction

package org.apache.tinkerpop.gremlin.tinkergraph.process.traversal;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Before;
import org.junit.Test;

import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Enhanced Integration Tests for Temporal Path Filtering
 * 
 * Uses clean DSL syntax.
 * Tests continuous, sequential, and pairwise-continuous path semantics.
 * 
 * @author Alex Spitalas
 */
public class TemporalPathIntegrationTest {
    
    private TinkerGraph graph;
    private GraphTraversalSource g;
    
    @Before
    public void setUp() {
        graph = TinkerGraph.open();
        g = graph.traversal();
    }

    
    // ==================== Test 1: 4-Edge Continuous Path ====================
    
    /**
     * CONTINUOUS PATH: All edges overlap at one time point
     * 
     * Timeline:
     * Edge 1: [10:00-12:00]
     * Edge 2:   [11:00-13:00]
     * Edge 3:     [11:30-12:30]
     * Edge 4:       [12:00-13:00]
     * 
     * Common intersection: [12:00-12:00] (single point) 
     */
    @Test
    public void testFourEdgeContinuousPath() {
        // Setup: Create 5 vertices
        Vertex vA = graph.addVertex("name", "josh");
        Vertex vB = graph.addVertex("name", "ripple");
        Vertex vC = graph.addVertex("name", "peter");
        Vertex vD = graph.addVertex("name", "mark");
        Vertex vE = graph.addVertex("name", "alice");
        
        // Add edges with overlapping time intervals
        vA.addEdge("follows", vB, "startTime", "2023-01-01T10:00:00", "endTime", "2023-01-01T12:00:00");
        vB.addEdge("follows", vC, "startTime", "2023-01-01T11:00:00", "endTime", "2023-01-01T13:00:00");
        vC.addEdge("follows", vD, "startTime", "2023-01-01T11:30:00", "endTime", "2023-01-01T12:30:00");
        vD.addEdge("follows", vE, "startTime", "2023-01-01T12:00:00", "endTime", "2023-01-01T13:00:00");
        
        List<Vertex> result = graph.traversal().V(vA)
            .continuousPath("follows")
            .continuousPath("follows")
            .continuousPath("follows")
            .continuousPath("follows")
            .toList();
        
        // Verify path reached the correct destination
        assertEquals("4-Edge Continuous Path Test", 1, result.size());
        assertEquals("alice", result.get(0).property("name").value());
    }
    
    // ==================== Test 2: 4-Edge Sequential Path with Gaps ====================
    
    /**
     * SEQUENTIAL PATH: Each edge starts ≥ when previous ends (causality)
     * Gaps are OK (time can pass between edges)
     * 
     * Timeline:
     * Edge 1: [10:00-11:00] ends
     * Gap: 30 minutes
     * Edge 2:              [11:30-12:00] ends
     * Gap: 15 minutes
     * Edge 3:                        [12:15-13:00] ends
     * Gap: 0 minutes (perfect handoff)
     * Edge 4:                               [13:00-14:00]
     * 
     * All handoffs valid: 11:00 ≤ 11:30, 12:00 ≤ 12:15, 13:00 ≤ 13:00
     */
    @Test
    public void testFourEdgeSequentialPathWithGaps() {
        // Setup: Create 5 vertices
        Vertex vA = graph.addVertex("name", "josh");
        Vertex vB = graph.addVertex("name", "ripple");
        Vertex vC = graph.addVertex("name", "peter");
        Vertex vD = graph.addVertex("name", "mark");
        Vertex vE = graph.addVertex("name", "alice");
        
        // Add sequential edges WITH GAPS (but respecting causality)
        vA.addEdge("follows", vB, "startTime", "2023-01-01T10:00:00", "endTime", "2023-01-01T11:00:00");
        vB.addEdge("follows", vC, "startTime", "2023-01-01T11:30:00", "endTime", "2023-01-01T12:00:00");
        vC.addEdge("follows", vD, "startTime", "2023-01-01T12:15:00", "endTime", "2023-01-01T13:00:00");
        vD.addEdge("follows", vE, "startTime", "2023-01-01T13:00:00", "endTime", "2023-01-01T14:00:00");
        
        List<Vertex> result = graph.traversal().V(vA)
            .sequentialPath("follows")
            .sequentialPath("follows")
            .sequentialPath("follows")
            .sequentialPath("follows")
            .toList();
        
        // Verify path reached the correct destination (gaps allowed in sequential)
        assertEquals("4-Edge Sequential Path Test", 1, result.size());
        assertEquals("alice", result.get(0).property("name").value());
    }
    
    // ==================== Test 3: 5-Edge Pairwise-Continuous Path ====================
    
    /**
     * PAIRWISE-CONTINUOUS PATH: Consecutive edges overlap at intermediate nodes
     * Unlike continuous, doesn't require ALL edges to overlap simultaneously
     * 
     * Timeline:
     * Edge 1: [10:00-12:00]
     * Edge 2:   [11:00-13:00] ← overlaps with e1
     * Edge 3:       [12:30-14:00] ← overlaps with e2
     * Edge 4:         [13:30-15:00] ← overlaps with e3
     * Edge 5:           [14:00-16:00] ← overlaps with e4
     * 
     * All consecutive pairs overlap
     * BUT: e1 and e5 do NOT overlap (that's OK for pairwise)
     */
    @Test
    public void testFiveEdgePairwiseContinuousPath() {
        // Setup: Create 6 vertices
        Vertex vA = graph.addVertex("name", "josh");
        Vertex vB = graph.addVertex("name", "ripple");
        Vertex vC = graph.addVertex("name", "peter");
        Vertex vD = graph.addVertex("name", "mark");
        Vertex vE = graph.addVertex("name", "alice");
        Vertex vF = graph.addVertex("name", "bob");
        
        // Add edges where consecutive pairs overlap
        vA.addEdge("follows", vB, "startTime", "2023-01-01T10:00:00", "endTime", "2023-01-01T12:00:00");
        vB.addEdge("follows", vC, "startTime", "2023-01-01T11:00:00", "endTime", "2023-01-01T13:00:00");
        vC.addEdge("follows", vD, "startTime", "2023-01-01T12:30:00", "endTime", "2023-01-01T14:00:00");
        vD.addEdge("follows", vE, "startTime", "2023-01-01T13:30:00", "endTime", "2023-01-01T15:00:00");
        vE.addEdge("follows", vF, "startTime", "2023-01-01T14:00:00", "endTime", "2023-01-01T16:00:00");
        
        List<Vertex> result = graph.traversal().V(vA)
            .pairwiseContinuousPath("follows")
            .pairwiseContinuousPath("follows")
            .pairwiseContinuousPath("follows")
            .pairwiseContinuousPath("follows")
            .pairwiseContinuousPath("follows")
            .toList();
        
        // Verify path reached the correct destination
        assertEquals("5-Edge Pairwise-Continuous Path Test", 1, result.size());
        assertEquals("bob", result.get(0).property("name").value());
    }
    
    // ==================== Test 4: Continuous Path Failure (Gap in Intersection) ====================
    
    /**
     * CONTINUOUS FAILURE: 3rd edge breaks the common intersection
     * 
     * Timeline:
     * Edge 1: [10:00-12:00]
     * Edge 2:   [11:00-13:00]
     * Edge 3:         [12:30-14:00] ← NO INTERSECTION with [11:00-12:00]!
     * Edge 4:           [13:00-15:00]
     * 
     * Step 1: [10:00-12:00] ∩ [11:00-13:00] = [11:00-12:00]
     * Step 2: [11:00-12:00] ∩ [12:30-14:00] = ∅ (EMPTY)
     * 
     * Continuous path FAILS at vD
     * Filter rejects edge 3, path terminates at vC
     */
    @Test
    public void testContinuousPathFailureGapInIntersection() {
        // Setup: Create 5 vertices
        Vertex vA = graph.addVertex("name", "josh");
        Vertex vB = graph.addVertex("name", "ripple");
        Vertex vC = graph.addVertex("name", "peter");
        Vertex vD = graph.addVertex("name", "mark");
        Vertex vE = graph.addVertex("name", "alice");
        
        // Add edges where 3rd edge breaks intersection
        vA.addEdge("follows", vB, "startTime", "2023-01-01T10:00:00", "endTime", "2023-01-01T12:00:00");
        vB.addEdge("follows", vC, "startTime", "2023-01-01T11:00:00", "endTime", "2023-01-01T13:00:00");
        vC.addEdge("follows", vD, "startTime", "2023-01-01T12:30:00", "endTime", "2023-01-01T14:00:00");  // Gap!
        vD.addEdge("follows", vE, "startTime", "2023-01-01T13:00:00", "endTime", "2023-01-01T15:00:00");
        
        // Try continuous path (should fail to reach vE)
        List<Vertex> result = graph.traversal().V(vA)
            .continuousPath("follows")
            .continuousPath("follows")
            .continuousPath("follows")
            .toList();
        
        // Path should terminate at vC (can't continue due to broken intersection)
        assertTrue("Continuous path rejected at vD (correct)", result.isEmpty());
    }
    
    // ==================== Test 5: Sequential Path Failure (Backward Time) ====================
    
    /**
     * SEQUENTIAL FAILURE: 4th edge violates causality (backward time)
     * 
     * Timeline:
     * Edge 1: [10:00-11:00]
     * Edge 2:      [11:00-12:00]
     * Edge 3:           [12:00-13:00]
     * Edge 4:      [12:30-14:00] ← STARTS BEFORE previous ends!
     * 
     * At edge 4:
     * Check: prevEnd (13:00) ≤ currStart (12:30)?
     * Result: 13:00 ≤ 12:30? FALSE 
     * 
     * Sequential path FAILS at vE
     * Filter rejects edge 4, path terminates at vD
     */
    @Test
    public void testSequentialPathFailureBackwardTime() {
        // Setup: Create 5 vertices
        Vertex vA = graph.addVertex("name", "josh");
        Vertex vB = graph.addVertex("name", "ripple");
        Vertex vC = graph.addVertex("name", "peter");
        Vertex vD = graph.addVertex("name", "mark");
        Vertex vE = graph.addVertex("name", "alice");
        
        // Add edges where 4th edge violates temporal causality
        vA.addEdge("follows", vB, "startTime", "2023-01-01T10:00:00", "endTime", "2023-01-01T11:00:00");
        vB.addEdge("follows", vC, "startTime", "2023-01-01T11:00:00", "endTime", "2023-01-01T12:00:00");
        vC.addEdge("follows", vD, "startTime", "2023-01-01T12:00:00", "endTime", "2023-01-01T13:00:00");
        vD.addEdge("follows", vE, "startTime", "2023-01-01T12:30:00", "endTime", "2023-01-01T14:00:00");  // Backward!
        
        // Try sequential path (should fail)
        List<Vertex> result = graph.traversal().V(vA)
            .sequentialPath("follows")
            .sequentialPath("follows")
            .sequentialPath("follows")
            .sequentialPath("follows")
            .toList();
        
        // Path should terminate at vD (can't continue - violates causality)
        assertTrue("Sequential path rejected at vE (correct)", result.isEmpty());
    }
    
    // ==================== Test 6: Pairwise Path Failure (Middle Gap) ====================
    
    /**
     * PAIRWISE FAILURE: 3rd edge has no overlap with 2nd edge
     * 
     * Timeline:
     * Edge 1: [10:00-12:00]
     * Edge 2:   [11:00-13:00] ← overlaps with e1 ✓
     * Edge 3:             [14:00-15:00] ← GAP! No overlap with e2
     * Edge 4:             [14:30-16:00]
     * 
     * At edge 3:
     * Check: e2 ∩ e3  NO OVERLAP
     * 
     * Pairwise path FAILS at vD
     * Filter rejects edge 3, path terminates at vC
     */
    @Test
    public void testPairwisePathFailureMiddleGap() {
        // Setup: Create 5 vertices
        Vertex vA = graph.addVertex("name", "josh");
        Vertex vB = graph.addVertex("name", "ripple");
        Vertex vC = graph.addVertex("name", "peter");
        Vertex vD = graph.addVertex("name", "mark");
        Vertex vE = graph.addVertex("name", "alice");
        
        // Add edges where 3rd edge has gap with 2nd
        vA.addEdge("follows", vB, "startTime", "2023-01-01T10:00:00", "endTime", "2023-01-01T12:00:00");
        vB.addEdge("follows", vC, "startTime", "2023-01-01T11:00:00", "endTime", "2023-01-01T13:00:00");
        vC.addEdge("follows", vD, "startTime", "2023-01-01T14:00:00", "endTime", "2023-01-01T15:00:00");  // Gap!
        vD.addEdge("follows", vE, "startTime", "2023-01-01T14:30:00", "endTime", "2023-01-01T16:00:00");
        
        // Try pairwise path (should fail)
        List<Vertex> result = graph.traversal().V(vA)
            .pairwiseContinuousPath("follows")
            .pairwiseContinuousPath("follows")
            .pairwiseContinuousPath("follows")
            .pairwiseContinuousPath("follows")
            .toList();
        
        // Path should terminate at vC (can't continue - gap at intermediate node)
        assertTrue("Pairwise path rejected at vD (correct)", result.isEmpty());
    }
    
    // ==================== Test 7: Semantic Difference (Sequential vs Continuous) ====================
    
    /**
     * SEMANTIC DIFFERENCE: Sequential succeeds where Continuous fails
     * 
     * This tests why difference between sequential and continuous paths.
     * 
     * Timeline (6 vertices, 5 edges):
     * Edge 1: [10:00-11:00]
     * Edge 2:      [11:00-12:00]
     * Edge 3:           [12:00-13:00]
     * Edge 4:                [13:00-14:00]
     * Edge 5:                     [14:00-15:00]
     * 
     * CONTINUOUS: All must overlap at same time
     *   Intersection: [10:00-11:00] ∩ [11:00-12:00] = ∅ (empty)
     *   Result: FAIL
     * 
     * SEQUENTIAL: Each starts ≥ previous ends
     *   11:00 ≤ 11:00, 12:00 ≤ 12:00, etc.
     *   Result: PASS
     */
    @Test
    public void testSemanticDifferenceSequentialVsContinuous() {
        // Setup: Create 6 vertices
        Vertex vA = graph.addVertex("name", "josh");
        Vertex vB = graph.addVertex("name", "ripple");
        Vertex vC = graph.addVertex("name", "peter");
        Vertex vD = graph.addVertex("name", "mark");
        Vertex vE = graph.addVertex("name", "alice");
        Vertex vF = graph.addVertex("name", "bob");
        
        // Add perfectly sequential edges (no overlaps)
        vA.addEdge("follows", vB, "startTime", "2023-01-01T10:00:00", "endTime", "2023-01-01T11:00:00");
        vB.addEdge("follows", vC, "startTime", "2023-01-01T11:00:00", "endTime", "2023-01-01T12:00:00");
        vC.addEdge("follows", vD, "startTime", "2023-01-01T12:00:00", "endTime", "2023-01-01T13:00:00");
        vD.addEdge("follows", vE, "startTime", "2023-01-01T13:00:00", "endTime", "2023-01-01T14:00:00");
        vE.addEdge("follows", vF, "startTime", "2023-01-01T14:00:00", "endTime", "2023-01-01T15:00:00");
        
        // SEQUENTIAL should succeed (perfect handoffs)
        List<Vertex> sequentialResult = graph.traversal().V(vA)
            .sequentialPath("follows")
            .sequentialPath("follows")
            .sequentialPath("follows")
            .sequentialPath("follows")
            .sequentialPath("follows")
            .toList();
        assertEquals("Sequential reaches vF", 1, sequentialResult.size());
        assertEquals("bob", sequentialResult.get(0).property("name").value());
        
        // CONTINUOUS should fail (no common time point)
        List<Vertex> continuousResult = graph.traversal().V(vA)
            .continuousPath("follows")
            .continuousPath("follows")
            .continuousPath("follows")
            .continuousPath("follows")
            .toList();
        assertTrue("Continuous fails (no overlap)", continuousResult.isEmpty());
    }
    // ==================== Test 8: Repeat Times Continuous Path Success ====================

    /**
     * REPEAT TIMES SUCCESS: loop continuousPath 3 times
     * 
     * Timeline:
     * Edge 1: [10:00-12:00]
     * Edge 2:   [11:00-13:00]
     * Edge 3:     [11:30-12:30]
     * 
     * Intersection: [11:30-12:00]
     */
    @Test
    public void testRepeatTimesContinuousPathSuccess() {
        Vertex vA = graph.addVertex("name", "josh");
        Vertex vB = graph.addVertex("name", "ripple");
        Vertex vC = graph.addVertex("name", "peter");
        Vertex vD = graph.addVertex("name", "mark");
        
        vA.addEdge("follows", vB, "startTime", "2023-01-01T10:00:00", "endTime", "2023-01-01T12:00:00");
        vB.addEdge("follows", vC, "startTime", "2023-01-01T11:00:00", "endTime", "2023-01-01T13:00:00");
        vC.addEdge("follows", vD, "startTime", "2023-01-01T11:30:00", "endTime", "2023-01-01T12:30:00");
        
        List<Vertex> result = graph.traversal().V(vA)
            .repeat(__.continuousPath("follows"))
            .times(3)
            .toList();
            
        assertEquals("Repeat Times(3) Success", 1, result.size());
        assertEquals("mark", result.get(0).property("name").value());
    }

    // ==================== Test 9: Repeat Times Continuous Path Failure ====================

    /**
     * REPEAT TIMES FAILURE: loop breaks continuity on 3rd edge
     * 
     * Timeline:
     * Edge 1: [10:00-12:00]
     * Edge 2:   [11:00-13:00]
     * Edge 3:         [12:30-14:00] ← No overlap with [11:00-12:00]
     */
    @Test
    public void testRepeatTimesContinuousPathFailure() {
        Vertex vA = graph.addVertex("name", "josh");
        Vertex vB = graph.addVertex("name", "ripple");
        Vertex vC = graph.addVertex("name", "peter");
        Vertex vD = graph.addVertex("name", "mark");
        
        vA.addEdge("follows", vB, "startTime", "2023-01-01T10:00:00", "endTime", "2023-01-01T12:00:00");
        vB.addEdge("follows", vC, "startTime", "2023-01-01T11:00:00", "endTime", "2023-01-01T13:00:00");
        vC.addEdge("follows", vD, "startTime", "2023-01-01T12:30:00", "endTime", "2023-01-01T14:00:00");
        
        List<Vertex> result = graph.traversal().V(vA)
            .repeat(__.continuousPath("follows"))
            .times(3)
            .toList();
            
        assertTrue("Repeat Times(3) Failure (correct)", result.isEmpty());
    }

    // ==================== Test 10: Repeat Until Continuous Path Success ====================

    /**
     * REPEAT UNTIL SUCCESS: loop until reaching a specific vertex
     * 
     * Same timeline as Test 8 (Success)
     */
    @Test
    public void testRepeatUntilContinuousPathSuccess() {
        Vertex vA = graph.addVertex("name", "josh");
        Vertex vB = graph.addVertex("name", "ripple");
        Vertex vC = graph.addVertex("name", "peter");
        Vertex vD = graph.addVertex("name", "mark");
        
        vA.addEdge("follows", vB, "startTime", "2023-01-01T10:00:00", "endTime", "2023-01-01T12:00:00");
        vB.addEdge("follows", vC, "startTime", "2023-01-01T11:00:00", "endTime", "2023-01-01T13:00:00");
        vC.addEdge("follows", vD, "startTime", "2023-01-01T11:30:00", "endTime", "2023-01-01T12:30:00");
        
        List<Vertex> result = graph.traversal().V(vA)
            .repeat(__.continuousPath("follows"))
            .until(__.has("name", "mark"))
            .toList();
            
        assertEquals("Repeat Until Success", 1, result.size());
        assertEquals("mark", result.get(0).property("name").value());
    }

    // ==================== Test 11: Repeat Until Continuous Path Failure ====================

    /**
     * REPEAT UNTIL FAILURE: continuity breaks in last step
     * 
     * Target: "mark" (depth 3)
     * Path breaks at depth 3 (Edge 3).
     * 
     * Timeline same as Test 9 (Failure).
     */
    @Test
    public void testRepeatUntilContinuousPathFailure() {
        Vertex vA = graph.addVertex("name", "josh");
        Vertex vB = graph.addVertex("name", "ripple");
        Vertex vC = graph.addVertex("name", "peter");
        Vertex vD = graph.addVertex("name", "mark");
        
        //3rd edge breaks continuous
        vA.addEdge("follows", vB, "startTime", "2023-01-01T10:00:00", "endTime", "2023-01-01T12:00:00");
        vB.addEdge("follows", vC, "startTime", "2023-01-01T11:00:00", "endTime", "2023-01-01T13:00:00");
        vC.addEdge("follows", vD, "startTime", "2023-01-01T12:30:00", "endTime", "2023-01-01T14:00:00");
        
        List<Vertex> result = graph.traversal().V(vA)
            .repeat(__.continuousPath("follows"))
            .until(__.has("name", "mark"))
            .toList();
            
        assertTrue("Repeat Until Failure (correct)", result.isEmpty());
    }
    // ==================== Test 12: Multiple Valid Paths from Branching Point ====================

    /**
     * BRANCHING PATHS: A->B->{C, D, E}
     * 
     * Timeline:
     * A->B: [10:00-12:00]
     * 
     * B->C: [11:00-11:30]  (Intersects A->B at 11:00-11:30) -> Valid
     * B->D: [11:30-12:00]  (Intersects A->B at 11:30-12:00) -> Valid
     * B->E: [12:30-13:00]  (No intersection with A->B)      -> Invalid
     * 
     * Result should be {C, D}
     */
    @Test
    public void testMultipleContinuousPathsFromSameOrigin() {
        Vertex vA = graph.addVertex("name", "A");
        Vertex vB = graph.addVertex("name", "B");
        Vertex vC = graph.addVertex("name", "C");
        Vertex vD = graph.addVertex("name", "D");
        Vertex vE = graph.addVertex("name", "E");

        // Common first leg
        vA.addEdge("follows", vB, "startTime", "2023-01-01T10:00:00", "endTime", "2023-01-01T12:00:00");

        // Branches
        vB.addEdge("follows", vC, "startTime", "2023-01-01T11:00:00", "endTime", "2023-01-01T11:30:00");
        vB.addEdge("follows", vD, "startTime", "2023-01-01T11:30:00", "endTime", "2023-01-01T12:00:00");
        vB.addEdge("follows", vE, "startTime", "2023-01-01T12:30:00", "endTime", "2023-01-01T13:00:00");

        List<Object> resultNames = graph.traversal().V(vA)
            .continuousPath("follows")
            .continuousPath("follows")
            .values("name")
            .toList();

        assertEquals("Should find exactly 2 valid paths", 2, resultNames.size());
        assertTrue("Should contain C", resultNames.contains("C"));
        assertTrue("Should contain D", resultNames.contains("D"));
        assertTrue("Should NOT contain E", !resultNames.contains("E"));
    }
}