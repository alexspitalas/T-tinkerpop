package org.apache.tinkerpop.gremlin.tinkergraph.structure;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class IntervalTreeTest {

    @Test
    public void shouldFindOverlapsCorrectly() {
        IntervalTree<String> tree = new IntervalTree<>();
        
        // Add events
        tree.put(100, 200, "Event1"); // 100 -> 200
        tree.put(150, 250, "Event2"); // 150 -> 250
        tree.put(300, 400, "Event3"); // 300 -> 400
        tree.put(50,  75,  "Event4"); // 50  -> 75
        tree.put(50,  75,  "Event5"); // Same interval

        // Query overlapping [180, 220]
        // Should intersect Event1 and Event2
        Set<String> result1 = tree.searchOverlaps(180, 220);
        assertEquals(2, result1.size());
        assertTrue(result1.contains("Event1"));
        assertTrue(result1.contains("Event2"));
        
        // Query overlapping [0, 40] -> None
        Set<String> result2 = tree.searchOverlaps(0, 40);
        assertTrue(result2.isEmpty());
        
        // Query overlapping [60, 60] -> Event4, Event5
        Set<String> result3 = tree.searchOverlaps(60, 60);
        assertEquals(2, result3.size());
        assertTrue(result3.contains("Event4"));
        assertTrue(result3.contains("Event5"));
    }

    @Test
    public void shouldRemoveCorrectly() {
        IntervalTree<String> tree = new IntervalTree<>();
        tree.put(100, 200, "Event1");
        tree.put(150, 250, "Event2");
        
        Set<String> res1 = tree.searchOverlaps(120, 180);
        assertEquals(2, res1.size());
        
        // Remove Event1
        tree.remove(100, 200, "Event1");
        
        Set<String> res2 = tree.searchOverlaps(120, 180);
        assertEquals(1, res2.size());
        assertTrue(res2.contains("Event2"));
        assertFalse(res2.contains("Event1"));
    }
}
