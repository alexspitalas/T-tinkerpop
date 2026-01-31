// TemporalPathIntegrationTest.java
// Using clean DSL syntax instead of manual step construction

package org.apache.tinkerpop.gremlin.tinkergraph.process.traversal;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Enhanced Integration Tests for Temporal Path Filtering
 * 
 * Uses clean DSL syntax (Option 1 & 2) instead of manual step construction.
 * Tests continuous, sequential, and pairwise-continuous path semantics.
 * 
 * Test Structure:
 * - 4-6 edges per test (not just 2)
 * - All three path types (continuous, sequential, pairwise)
 * - Both PASS and FAIL test cases
 * - Demonstrates semantic differences
 */
public class TemporalPathIntegrationTest {
    
    private TinkerGraph graph;
    private GraphTraversalSource g;
    private DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    
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
     * Common intersection: [12:00-12:00] (single point) ✓
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
        
        // OPTION 1: DSL with where predicate (Recommended)
        List<Vertex> result = graph.traversal().V(vA)
            .continuousPath("follows")
            .continuousPath("follows")
            .continuousPath("follows")
            .continuousPath("follows")
            .toList();
        
        // Verify path reached the correct destination
        assertEquals("✅ 4-Edge Continuous Path Test", 1, result.size());
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
     * All handoffs valid: 11:00 ≤ 11:30, 12:00 ≤ 12:15, 13:00 ≤ 13:00 ✓
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
        
        // OPTION 2: Traversal builder method (Cleaner syntax)
        List<Vertex> result = graph.traversal().V(vA)
            .sequentialPath("follows")
            .sequentialPath("follows")
            .sequentialPath("follows")
            .sequentialPath("follows")
            .toList();
        
        // Verify path reached the correct destination (gaps allowed in sequential)
        assertEquals("✅ 4-Edge Sequential Path Test", 1, result.size());
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
     * All consecutive pairs overlap ✓
     * BUT: e1 and e5 do NOT overlap (that's OK for pairwise) ✓
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
        
        // OPTION 1: Clean DSL
        List<Vertex> result = graph.traversal().V(vA)
            .pairwiseContinuousPath("follows")
            .pairwiseContinuousPath("follows")
            .pairwiseContinuousPath("follows")
            .pairwiseContinuousPath("follows")
            .pairwiseContinuousPath("follows")
            .toList();
        
        // Verify path reached the correct destination
        assertEquals("✅ 5-Edge Pairwise-Continuous Path Test", 1, result.size());
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
     * Step 2: [11:00-12:00] ∩ [12:30-14:00] = ∅ (EMPTY) ❌
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
        assertTrue("❌ Continuous path rejected at vD (correct)", result.isEmpty());
    }
    
    // ==================== Test 5: Sequential Path Failure (Backward Time) ====================
    
    /**
     * SEQUENTIAL FAILURE: 4th edge violates causality (backward time)
     * 
     * Timeline:
     * Edge 1: [10:00-11:00]
     * Edge 2:      [11:00-12:00]
     * Edge 3:           [12:00-13:00]
     * Edge 4:      [12:30-14:00] ← STARTS BEFORE previous ends! ❌
     * 
     * At edge 4:
     * Check: prevEnd (13:00) ≤ currStart (12:30)?
     * Result: 13:00 ≤ 12:30? FALSE ❌
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
        assertTrue("❌ Sequential path rejected at vE (correct)", result.isEmpty());
    }
    
    // ==================== Test 6: Pairwise Path Failure (Middle Gap) ====================
    
    /**
     * PAIRWISE FAILURE: 3rd edge has no overlap with 2nd edge
     * 
     * Timeline:
     * Edge 1: [10:00-12:00]
     * Edge 2:   [11:00-13:00] ← overlaps with e1 ✓
     * Edge 3:             [14:00-15:00] ← GAP! No overlap with e2 ❌
     * Edge 4:             [14:30-16:00]
     * 
     * At edge 3:
     * Check: e2 ∩ e3 overlap?
     * e2: [11:00-13:00], e3: [14:00-15:00]
     * Result: max(11:00, 14:00) = 14:00, min(13:00, 15:00) = 13:00
     * Result: 14:00 > 13:00? NO OVERLAP ❌
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
        assertTrue("❌ Pairwise path rejected at vD (correct)", result.isEmpty());
    }
    
    // ==================== Test 7: Semantic Difference (Sequential vs Continuous) ====================
    
    /**
     * SEMANTIC DIFFERENCE: Sequential succeeds where Continuous fails
     * 
     * This demonstrates why all three path types exist.
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
     *   Result: FAIL ❌
     * 
     * SEQUENTIAL: Each starts ≥ previous ends
     *   11:00 ≤ 11:00 ✓, 12:00 ≤ 12:00 ✓, etc.
     *   Result: PASS ✅
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
        assertEquals("✅ Sequential reaches vF", 1, sequentialResult.size());
        assertEquals("bob", sequentialResult.get(0).property("name").value());
        
        // CONTINUOUS should fail (no common time point)
        List<Vertex> continuousResult = graph.traversal().V(vA)
            .continuousPath("follows")
            .continuousPath("follows")
            .continuousPath("follows")
            .continuousPath("follows")
            .toList();
        assertTrue("❌ Continuous fails (no overlap)", continuousResult.isEmpty());
    }
}