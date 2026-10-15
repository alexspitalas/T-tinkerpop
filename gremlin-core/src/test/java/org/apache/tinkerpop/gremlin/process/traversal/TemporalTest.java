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

package org.apache.tinkerpop.gremlin.process.traversal;

import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime.Interval;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TemporalTest {

    private Lifetime makeLifetime(long... times) {
        List<Interval> intervals = new ArrayList<>();
        for (int i = 0; i < times.length; i += 2) {
            intervals.add(new Interval(new Date(times[i]), new Date(times[i+1])));
        }
        return Lifetime.from(intervals);
    }

    @Test
    public void shouldEvaluateIntersects() {
        // A has a gap from 200 to 300
        Lifetime a = makeLifetime(100, 200, 300, 400);
        
        // B falls right into the gap
        Lifetime b = makeLifetime(210, 290);
        assertFalse("B is in the gap, should not intersect", Temporal.intersects.test(a, b));
        
        // C touches the gap boundaries but doesn't cross (depends on open/closed semantics, 
        // usually bounds are exclusive or identical bounds don't intersect unless active)
        // Actually, let's use an explicit overlap
        Lifetime c = makeLifetime(150, 250);
        assertTrue("C overlaps the first interval", Temporal.intersects.test(a, c));
    }

    @Test
    public void shouldEvaluateDuringAndContainsMultiInterval() {
        // C has a gap from 200 to 300
        Lifetime c = makeLifetime(100, 200, 300, 400);
        
        // B is [210, 290]. Its outer bounds (210-290) are strictly inside C's outer bounds (100-400).
        // But B is entirely in the gap!
        Lifetime b = makeLifetime(210, 290);
        assertFalse("B falls in the gap, so it is NOT during C", Temporal.during.test(b, c));
        assertFalse(Temporal.contains.test(c, b));
        
        // D is perfectly covered by C's active intervals
        Lifetime d = makeLifetime(120, 180, 320, 380);
        assertTrue("D is perfectly covered by C's sub-intervals", Temporal.during.test(d, c));
        assertTrue(Temporal.contains.test(c, d));
        
        // E starts inside but bleeds into the gap
        Lifetime e = makeLifetime(150, 250);
        assertFalse("E bleeds into the gap, not completely covered", Temporal.during.test(e, c));
    }

    @Test
    public void shouldEvaluatePrecedesAndSucceeds() {
        Lifetime a = makeLifetime(100, 200);
        Lifetime b = makeLifetime(300, 400);
        
        assertTrue(Temporal.precedes.test(a, b));
        assertTrue(Temporal.succeeds.test(b, a));
        
        // Meets is exactly touching
        Lifetime c = makeLifetime(200, 300);
        assertFalse(Temporal.precedes.test(a, c)); // Strict before
        assertTrue(Temporal.meets.test(a, c));
        assertTrue(Temporal.metBy.test(c, a));
    }
    
    @Test
    public void shouldEvaluateOverlapsStrict() {
        // Allen's strict overlaps: A starts before B, ends before B, but they intersect
        Lifetime a = makeLifetime(100, 200);
        Lifetime b = makeLifetime(150, 250);
        
        assertTrue(Temporal.overlaps.test(a, b));
        assertFalse("B starts after A, so it does not strictly overlap A", Temporal.overlaps.test(b, a));
    }
}
