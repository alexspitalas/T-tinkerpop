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

import static org.junit.Assert.assertEquals;
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

    @Test
    public void shouldDistinguishDuringFromStartsAndFinishes() {
        Lifetime outer = makeLifetime(100, 300);
        Lifetime startsInner = makeLifetime(100, 200);
        Lifetime finishesInner = makeLifetime(200, 300);
        Lifetime strictInner = makeLifetime(150, 250);

        assertTrue(Temporal.starts.test(startsInner, outer));
        assertFalse("starts should not also satisfy strict during", Temporal.during.test(startsInner, outer));
        assertFalse("startedBy should not also satisfy strict contains", Temporal.contains.test(outer, startsInner));

        assertTrue(Temporal.finishes.test(finishesInner, outer));
        assertFalse("finishes should not also satisfy strict during", Temporal.during.test(finishesInner, outer));
        assertFalse("finishedBy should not also satisfy strict contains", Temporal.contains.test(outer, finishesInner));

        assertTrue(Temporal.during.test(strictInner, outer));
        assertTrue(Temporal.contains.test(outer, strictInner));
    }

    @Test
    public void shouldSupportIntervalxIntervalAndIntervalxMultiIntervalObject() {
        final Interval i1 = new Interval(new Date(100), new Date(200));
        final Interval i2 = new Interval(new Date(200), new Date(300));
        final Interval i3 = new Interval(new Date(150), new Date(250));

        // i1 x i2 (Interval x Interval)
        assertTrue(Temporal.meets.test(i1, i2));
        assertTrue(Temporal.metBy.test(i2, i1));
        assertTrue(Temporal.overlaps.test(i1, i3));
        assertTrue(Temporal.overlappedBy.test(i3, i1));
        assertTrue(TemporalP.meets(i2).test(i1));
        assertTrue(TemporalP.overlaps(i3).test(i1));

        // i1 x o1 and o1 x i1 (Interval x Multi-interval Lifetime)
        final Lifetime o1 = makeLifetime(50, 250, 400, 500);
        assertTrue(Temporal.during.test(i1, o1));
        assertTrue(Temporal.contains.test(o1, i1));
        assertTrue(TemporalP.during(o1).test(i1));
        assertTrue(TemporalP.contains(i1).test(o1));
    }

    @Test
    public void shouldSupportConvexRelationsDisjointAndVilainPointPredicates() {
        final Lifetime o1 = makeLifetime(100, 200, 300, 400);
        final Interval gapInterval = new Interval(new Date(210), new Date(290));
        final Interval subInterval = new Interval(new Date(100), new Date(200));

        // disjoint: gapInterval sits in o1's gap [200, 300), so they are disjoint!
        assertTrue(Temporal.disjoint.test(gapInterval, o1));
        assertTrue(Temporal.disjoint.test(o1, gapInterval));
        assertFalse(Temporal.disjoint.test(subInterval, o1));

        // Convex lattice intervals expressed via TemporalP.between(lower, upper, target)
        // enclosedBy = [starts, finishes] = [(1,2), (2,3)]
        final Interval outerInterval = new Interval(new Date(100), new Date(300));
        assertTrue(TemporalP.between(Temporal.starts, Temporal.finishes, outerInterval).test(subInterval));
        assertTrue(TemporalP.between(Temporal.finishedBy, Temporal.startedBy, subInterval).test(outerInterval));

        // precedesOrMeets = [precedes, meets] = [(0,0), (0,1)]
        final Interval abutting = new Interval(new Date(200), new Date(300));
        assertTrue(TemporalP.between(Temporal.precedes, Temporal.meets, abutting).test(subInterval));
        assertTrue(TemporalP.between(Temporal.metBy, Temporal.succeeds, subInterval).test(abutting));

        // Vilain \Pi(1, 2) point-to-interval relations expressed directly via standard TemporalP operators
        // (since extractLifetime(Date) treats a point t as [t, t] against half-open [100, 200))
        assertTrue(TemporalP.precedes(subInterval).test(new Date(50)));   // t < 100 (POINT_BEFORE)
        assertTrue(TemporalP.starts(subInterval).test(new Date(100)));    // t == 100 (POINT_BEGINS)
        assertTrue(TemporalP.during(subInterval).test(new Date(150)));    // 100 < t < 200 (POINT_DURING)
        assertTrue(TemporalP.metBy(subInterval).test(new Date(200)));     // t == 200 (POINT_ENDS)
        assertTrue(TemporalP.succeeds(subInterval).test(new Date(250)));  // t > 200 (POINT_AFTER)

        // And via GeneralizedRelation.POINT_* with TemporalP.generalized + transpose()
        assertTrue(TemporalP.generalized(new Date(200),
                org.apache.tinkerpop.gremlin.structure.temporal.GeneralizedRelation.POINT_ENDS).transpose().test(subInterval));
        assertEquals(TemporalP.between(Temporal.precedes, Temporal.meets, abutting),
                TemporalP.between(Temporal.metBy, Temporal.succeeds, abutting).transpose());

        // Quantifier.ALL (default) vs Quantifier.ANY (.any()) over multi-interval objects
        // o1 = {[100, 200), [300, 400)}, gapInterval = [210, 290)
        // Under ALL (holistic), o1 is neither precedes nor succeeds gapInterval; under ANY, it satisfies both!
        assertFalse(TemporalP.precedes(gapInterval).test(o1));
        assertFalse(TemporalP.succeeds(gapInterval).test(o1));
        assertTrue(TemporalP.precedes(gapInterval).any().test(o1));
        assertTrue(TemporalP.succeeds(gapInterval).any().test(o1));
        assertTrue(TemporalP.any(Temporal.precedes, gapInterval).test(o1));

        // Partial containment: xPartial = {[120, 180), [500, 600)} vs subInterval = [100, 200)
        final Lifetime xPartial = makeLifetime(120, 180, 500, 600);
        assertFalse(TemporalP.during(subInterval).test(xPartial));       // ALL: [500, 600) is outside [100, 200)
        assertTrue(TemporalP.during(subInterval).any().test(xPartial));  // ANY: [120, 180) is during [100, 200)
        assertTrue(TemporalP.contains(xPartial).any().test(subInterval));
        assertEquals(TemporalP.during(subInterval).any(), TemporalP.contains(subInterval).any().transpose());

        // Meets / MetBy under ANY when an internal interval meets
        assertFalse(TemporalP.meets(abutting).test(o1));        // ALL: outer end is 400 != 200
        assertTrue(TemporalP.meets(abutting).any().test(o1));   // ANY: [100, 200) meets [200, 300)
        assertTrue(TemporalP.metBy(o1).any().test(abutting));
    }
}
