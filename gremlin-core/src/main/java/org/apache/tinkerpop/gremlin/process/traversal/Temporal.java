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

import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;


import java.util.Date;
import java.util.List;

/**
 * {@link Temporal} is a {@code BiPredicate} that implements Allen's Interval Algebra 
 * extended with algorithmic sweeps to perfectly support multi-interval lifetimes.
 */
public enum Temporal implements PBiPredicate<Object, Object> {

    /** General intersection. True if any interval of A overlaps any interval of B. */
    intersects {
        @Override
        public boolean test(final Object val1, final Object val2) {
            return extractLifetime(val1).intersects(extractLifetime(val2));
        }
    },
    
    /** Allen's strict Overlaps: A starts before B, ends before B, but they intersect. */
    overlaps { 
        @Override
        public boolean test(final Object val1, final Object val2) {
            final Lifetime l1 = extractLifetime(val1);
            final Lifetime l2 = extractLifetime(val2);
            if (l1 == null || l2 == null) return false;
            return l1.getStartDate().before(l2.getStartDate()) && 
                   l1.getEndDate().before(l2.getEndDate()) && 
                   l1.intersects(l2);
        }
    },
    
    precedes {
        @Override
        public boolean test(final Object val1, final Object val2) {
            final Lifetime l1 = extractLifetime(val1);
            final Lifetime l2 = extractLifetime(val2);
            if (l1 == null || l2 == null) return false;
            return l1.getEndDate().before(l2.getStartDate());
        }
    },
    
    succeeds {
        @Override
        public boolean test(final Object val1, final Object val2) {
            final Lifetime l1 = extractLifetime(val1);
            final Lifetime l2 = extractLifetime(val2);
            if (l1 == null || l2 == null) return false;
            return l1.getStartDate().after(l2.getEndDate());
        }
    },
    
    overlappedBy {
        @Override
        public boolean test(final Object val1, final Object val2) {
            return overlaps.test(val2, val1);
        }
    },

    meets {
        @Override
        public boolean test(final Object val1, final Object val2) {
            final Lifetime l1 = extractLifetime(val1);
            final Lifetime l2 = extractLifetime(val2);
            if (l1 == null || l2 == null) return false;
            return l1.getEndDate().equals(l2.getStartDate());
        }
    },
    
    metBy {
        @Override
        public boolean test(final Object val1, final Object val2) {
            final Lifetime l1 = extractLifetime(val1);
            final Lifetime l2 = extractLifetime(val2);
            if (l1 == null || l2 == null) return false;
            return l1.getStartDate().equals(l2.getEndDate());
        }
    },
    
    /** A is strictly contained within B (Multi-interval: every interval of A is fully inside an interval of B) */
    during {
        @Override
        public boolean test(final Object val1, final Object val2) {
            final Lifetime l1 = extractLifetime(val1);
            final Lifetime l2 = extractLifetime(val2);
            if (l1 == null || l2 == null) return false;
            // A must be a subset, and outer bounds cannot be exactly identical to count as strictly 'during'
            return l2.contains(l1) && (l1.getStartDate().after(l2.getStartDate()) || l1.getEndDate().before(l2.getEndDate()));
        }
    },
    
    contains {
        @Override
        public boolean test(final Object val1, final Object val2) {
            return during.test(val2, val1);
        }
    },
    
    eq {
        @Override
        public boolean test(final Object val1, final Object val2) {
            final Lifetime l1 = extractLifetime(val1);
            final Lifetime l2 = extractLifetime(val2);
            if (l1 == null || l2 == null) return false;
            if (!l1.getStartDate().equals(l2.getStartDate()) || !l1.getEndDate().equals(l2.getEndDate())) return false;
            
            List<Lifetime.Interval> i1s = l1.getIntervals();
            List<Lifetime.Interval> i2s = l2.getIntervals();
            if (i1s.size() != i2s.size()) return false;
            
            for (int i = 0; i < i1s.size(); i++) {
                if (!i1s.get(i).getStart().equals(i2s.get(i).getStart()) || 
                    !i1s.get(i).getEnd().equals(i2s.get(i).getEnd())) return false;
            }
            return true;
        }
    },
    
    starts {
        @Override
        public boolean test(final Object val1, final Object val2) {
            final Lifetime l1 = extractLifetime(val1);
            final Lifetime l2 = extractLifetime(val2);
            if (l1 == null || l2 == null) return false;
            return l1.getStartDate().equals(l2.getStartDate()) && 
                   l1.getEndDate().before(l2.getEndDate()) && 
                   l2.contains(l1);
        }
    },
    
    startedBy {
        @Override
        public boolean test(final Object val1, final Object val2) {
            return starts.test(val2, val1);
        }
    },
    
    finishes {
        @Override
        public boolean test(final Object val1, final Object val2) {
            final Lifetime l1 = extractLifetime(val1);
            final Lifetime l2 = extractLifetime(val2);
            if (l1 == null || l2 == null) return false;
            return l1.getEndDate().equals(l2.getEndDate()) && 
                   l1.getStartDate().after(l2.getStartDate()) && 
                   l2.contains(l1);
        }
    },
    
    finishedBy {
        @Override
        public boolean test(final Object val1, final Object val2) {
            return finishes.test(val2, val1);
        }
    };

    protected static Lifetime extractLifetime(final Object val) {
        if (val instanceof Lifetime) {
            return (Lifetime) val;
        } else if (val instanceof Element) {
            return Lifetime.fromProperties((Element) val);
        } else if (val instanceof Date) {
            return Lifetime.from(val, val);
        }
        return null;
    }
}
