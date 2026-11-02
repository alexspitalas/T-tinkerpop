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

        // Abutting boundary queries under half-open semantics [start, end)
        // [200, 300) abuts Event1 [100, 200) and Event3 [300, 400), but strictly overlaps only Event2 [150, 250)
        Set<String> result4 = tree.searchOverlaps(200, 300);
        assertEquals(1, result4.size());
        assertTrue(result4.contains("Event2"));

        // Point query at end boundary 200: Event1 [100, 200) excluded, Event2 [150, 250) included
        Set<String> result5 = tree.searchOverlaps(200, 200);
        assertEquals(1, result5.size());
        assertTrue(result5.contains("Event2"));

        // Point query at end boundary 75: Event4 and Event5 [50, 75) excluded
        Set<String> result6 = tree.searchOverlaps(75, 75);
        assertTrue(result6.isEmpty());
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

    @Test
    public void shouldSupportLigozatEndpointSearchMethods() {
        IntervalTree<String> tree = new IntervalTree<>();
        tree.put(50, 100, "E1");   // [50, 100)
        tree.put(100, 200, "E2");  // [100, 200)
        tree.put(150, 200, "E3");  // [150, 200)
        tree.put(200, 300, "E4");  // [200, 300)
        tree.put(300, 300, "P1");  // Point interval [300, 300]
        tree.put(300, 450, "E5");  // [300, 450)
        tree.put(400, 500, "E6");  // [400, 500)

        // searchEndingBefore(200): end < 200 -> E1 (end=100)
        Set<String> endingBefore200 = tree.searchEndingBefore(200);
        assertEquals(1, endingBefore200.size());
        assertTrue(endingBefore200.contains("E1"));

        // searchEndingAtOrBefore(200): end <= 200 -> E1, E2, E3
        Set<String> endingAtOrBefore200 = tree.searchEndingAtOrBefore(200);
        assertEquals(3, endingAtOrBefore200.size());
        assertTrue(endingAtOrBefore200.contains("E1"));
        assertTrue(endingAtOrBefore200.contains("E2"));
        assertTrue(endingAtOrBefore200.contains("E3"));

        // searchByEndTime(200): end == 200 -> E2, E3
        Set<String> endAt200 = tree.searchByEndTime(200);
        assertEquals(2, endAt200.size());
        assertTrue(endAt200.contains("E2"));
        assertTrue(endAt200.contains("E3"));

        // searchByEndTime(300): end == 300 -> E4, P1
        Set<String> endAt300 = tree.searchByEndTime(300);
        assertEquals(2, endAt300.size());
        assertTrue(endAt300.contains("E4"));
        assertTrue(endAt300.contains("P1"));

        // searchStartingAfter(300): start > 300 -> E6 (start=400)
        Set<String> startingAfter300 = tree.searchStartingAfter(300);
        assertEquals(1, startingAfter300.size());
        assertTrue(startingAfter300.contains("E6"));

        // searchStartingAtOrAfter(300): start >= 300 -> P1, E5, E6
        Set<String> startingAtOrAfter300 = tree.searchStartingAtOrAfter(300);
        assertEquals(3, startingAtOrAfter300.size());
        assertTrue(startingAtOrAfter300.contains("P1"));
        assertTrue(startingAtOrAfter300.contains("E5"));
        assertTrue(startingAtOrAfter300.contains("E6"));

        // searchByStartTime(300): start == 300 -> P1, E5
        Set<String> startAt300 = tree.searchByStartTime(300);
        assertEquals(2, startAt300.size());
        assertTrue(startAt300.contains("P1"));
        assertTrue(startAt300.contains("E5"));
    }
}
