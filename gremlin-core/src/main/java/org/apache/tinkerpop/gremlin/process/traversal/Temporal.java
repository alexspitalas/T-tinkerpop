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
import org.apache.tinkerpop.gremlin.structure.temporal.GeneralizedIntervalRelation;
import org.apache.tinkerpop.gremlin.structure.temporal.GeneralizedRelation;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;


import java.util.Date;
import java.util.List;

/**
 * {@link Temporal} is a {@code BiPredicate} that implements Allen's Interval Algebra
 * and Gérard Ligozat's (AAAI-91) Generalized Interval Calculus (including convex lattice intervals,
 * Vilain's point-interval relations, and multi-interval sweep logic).
 */
public enum Temporal implements PBiPredicate<Object, Object> {

    /** General intersection (Ligozat convex interval [(0,2), (2,4)] in Pi(2,2)). True if any interval of A overlaps any interval of B. */
    intersects {
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            if (l1 == null || l2 == null) return false;
            return l1.intersects(l2);
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return test(extractLifetime(val1), extractLifetime(val2));
        }
    },

    /** Complement of intersects: True if no active interval of A overlaps any active interval of B (including when A's intervals sit inside B's gaps). */
    disjoint {
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            if (l1 == null || l2 == null || l1.isEmpty() || l2.isEmpty()) return false;
            return !l1.intersects(l2);
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return test(extractLifetime(val1), extractLifetime(val2));
        }
    },

    /** Allen's strict Overlaps: A starts before B, ends before B, but they intersect. */
    overlaps { 
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            if (l1 == null || l2 == null || l1.isEmpty() || l2.isEmpty()) return false;
            return l1.getStartMillis() < l2.getStartMillis() && 
                   l1.getEndMillis() < l2.getEndMillis() && 
                   l1.intersects(l2);
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return test(extractLifetime(val1), extractLifetime(val2));
        }
    },
    
    precedes {
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            if (l1 == null || l2 == null || l1.isEmpty() || l2.isEmpty()) return false;
            return l1.getEndMillis() < l2.getStartMillis();
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return test(extractLifetime(val1), extractLifetime(val2));
        }
    },
    
    succeeds {
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            if (l1 == null || l2 == null || l1.isEmpty() || l2.isEmpty()) return false;
            return l1.getStartMillis() > l2.getEndMillis();
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return test(extractLifetime(val1), extractLifetime(val2));
        }
    },
    
    overlappedBy {
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            return overlaps.test(l2, l1);
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return overlaps.test(extractLifetime(val2), extractLifetime(val1));
        }
    },

    meets {
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            if (l1 == null || l2 == null || l1.isEmpty() || l2.isEmpty()) return false;
            return l1.getEndMillis() == l2.getStartMillis();
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return test(extractLifetime(val1), extractLifetime(val2));
        }
    },
    
    metBy {
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            if (l1 == null || l2 == null || l1.isEmpty() || l2.isEmpty()) return false;
            return l1.getStartMillis() == l2.getEndMillis();
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return test(extractLifetime(val1), extractLifetime(val2));
        }
    },
    
    /** A is strictly contained within B (Multi-interval: every interval of A is fully inside an interval of B) */
    during {
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            if (l1 == null || l2 == null || l1.isEmpty() || l2.isEmpty()) return false;
            return l2.contains(l1) && (l1.getStartMillis() > l2.getStartMillis() && l1.getEndMillis() < l2.getEndMillis());
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return test(extractLifetime(val1), extractLifetime(val2));
        }
    },
    
    contains {
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            return during.test(l2, l1);
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return during.test(extractLifetime(val2), extractLifetime(val1));
        }
    },
    
    eq {
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            if (l1 == null || l2 == null) return false;
            return !l1.isEmpty() && l1.equals(l2);
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return test(extractLifetime(val1), extractLifetime(val2));
        }
    },
    
    starts {
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            if (l1 == null || l2 == null || l1.isEmpty() || l2.isEmpty()) return false;
            return l1.getStartMillis() == l2.getStartMillis() && 
                   l1.getEndMillis() < l2.getEndMillis() && 
                   l2.contains(l1);
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return test(extractLifetime(val1), extractLifetime(val2));
        }
    },
    
    startedBy {
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            return starts.test(l2, l1);
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return starts.test(extractLifetime(val2), extractLifetime(val1));
        }
    },
    
    finishes {
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            if (l1 == null || l2 == null || l1.isEmpty() || l2.isEmpty()) return false;
            return l1.getEndMillis() == l2.getEndMillis() && 
                   l1.getStartMillis() > l2.getStartMillis() && 
                   l2.contains(l1);
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return test(extractLifetime(val1), extractLifetime(val2));
        }
    },
    
    finishedBy {
        @Override
        public boolean test(final Lifetime l1, final Lifetime l2) {
            return finishes.test(l2, l1);
        }

        @Override
        public boolean test(final Object val1, final Object val2) {
            return finishes.test(extractLifetime(val2), extractLifetime(val1));
        }
    },

    /**
     * Evaluates Gérard Ligozat's generalized {@code (p, q)}-relation or lattice interval {@code [\alpha, \beta]}.
     * {@code val2} may be a {@code List} {@code [referenceTarget, relationOrInterval]}, or when used via
     * {@link TemporalP#generalized(Object, GeneralizedRelation)} / {@link TemporalP#generalized(Object, GeneralizedIntervalRelation)},
     * {@link TemporalP#test(Object)} delegates directly to the bound {@link GeneralizedRelation} or
     * {@link GeneralizedIntervalRelation}.
     */
    generalized {
        @Override
        public boolean test(final Object val1, final Object val2) {
            if (val2 instanceof List) {
                final List<?> pair = (List<?>) val2;
                if (pair.size() == 2) {
                    final Object target = pair.get(0);
                    final Object spec = pair.get(1);
                    if (spec instanceof GeneralizedRelation) {
                        return ((GeneralizedRelation) spec).test(val1, target);
                    } else if (spec instanceof GeneralizedIntervalRelation) {
                        return ((GeneralizedIntervalRelation) spec).test(val1, target);
                    }
                }
            } else if (val2 instanceof GeneralizedRelation) {
                return ((GeneralizedRelation) val2).test(val1, val1);
            } else if (val2 instanceof GeneralizedIntervalRelation) {
                return ((GeneralizedIntervalRelation) val2).test(val1, val1);
            }
            return false;
        }
    };

    /**
     * Step configuration key and values for modulating {@link org.apache.tinkerpop.gremlin.process.traversal.step.filter.AllenFilterStep}
     * via {@code .with(Temporal.ANY)} or {@code .with(Temporal.quantifier, Temporal.Quantifier.ANY)}.
     */
    public static final String quantifier = "~tinkerpop.temporal.quantifier";
    public static final String ANY = "ANY";
    public static final String ALL = "ALL";

    /**
     * Quantifier governing how multi-interval lifetimes {@code X = {x_1, ..., x_m}} and
     * {@code Y = {y_1, ..., y_n}} are evaluated under an Allen relation {@code R}:
     * <ul>
     *     <li>{@link #ALL} (default): Holistic whole-lifetime semantics treating {@code \tau(X)} and {@code \tau(Y)}
     *     as complete validity sets (e.g., {@code precedes} requires all of {@code X} before all of {@code Y};
     *     {@code during} requires {@code \tau(X) \subseteq \tau(Y)}).</li>
     *     <li>{@link #ANY}: Existential sub-interval pair semantics ({@code \exists x_i \in X, \exists y_j \in Y : R(x_i, y_j)}).</li>
     * </ul>
     */
    public enum Quantifier {
        ALL,
        ANY
    }

    /**
     * Evaluates this temporal relation on two single {@link Lifetime.Interval}s {@code x} and {@code y}.
     */
    public boolean testIntervalPair(final Lifetime.Interval x, final Lifetime.Interval y) {
        if (x == null || y == null) return false;
        return testIntervalPair(x.getStartMillis(), x.getEndMillis(), y.getStartMillis(), y.getEndMillis());
    }

    /**
     * Evaluates this temporal relation on primitive start and end epoch millisecond endpoints. Zero heap allocations.
     */
    public boolean testIntervalPair(final long xStart, final long xEnd, final long yStart, final long yEnd) {
        switch (this) {
            case intersects:   return intersectsRange(xStart, xEnd, yStart, yEnd);
            case disjoint:     return !intersectsRange(xStart, xEnd, yStart, yEnd);
            case precedes:     return xEnd < yStart;
            case succeeds:     return xStart > yEnd;
            case meets:        return xEnd == yStart;
            case metBy:        return xStart == yEnd;
            case overlaps:     return xStart < yStart && xEnd < yEnd && intersectsRange(xStart, xEnd, yStart, yEnd);
            case overlappedBy: return yStart < xStart && yEnd < xEnd && intersectsRange(yStart, yEnd, xStart, xEnd);
            case during:       return containsRange(yStart, yEnd, xStart, xEnd) && xStart > yStart && xEnd < yEnd;
            case contains:     return containsRange(xStart, xEnd, yStart, yEnd) && yStart > xStart && yEnd < xEnd;
            case eq:           return xStart == yStart && xEnd == yEnd;
            case starts:       return xStart == yStart && xEnd < yEnd && containsRange(yStart, yEnd, xStart, xEnd);
            case startedBy:    return xStart == yStart && yEnd < xEnd && containsRange(xStart, xEnd, yStart, yEnd);
            case finishes:     return xEnd == yEnd && xStart > yStart && containsRange(yStart, yEnd, xStart, xEnd);
            case finishedBy:   return xEnd == yEnd && yStart > xStart && containsRange(xStart, xEnd, yStart, yEnd);
            default:           return false;
        }
    }

    private static boolean containsInstant(final long start, final long end, final long instant) {
        if (start == end) return start == instant;
        return instant >= start && instant < end;
    }

    private static boolean containsRange(final long startA, final long endA, final long startB, final long endB) {
        if (startB == endB) return containsInstant(startA, endA, startB);
        if (startA == endA) return false;
        return startA <= startB && endA >= endB;
    }

    private static boolean intersectsRange(final long startA, final long endA, final long startB, final long endB) {
        if (startA == endA) {
            if (startB == endB) return startA == startB;
            return startB <= startA && startA < endB;
        }
        if (startB == endB) {
            return startA <= startB && startB < endA;
        }
        return startA < endB && startB < endA;
    }

    /**
     * Evaluates this temporal relation on two {@link Lifetime} instances directly without object extraction.
     */
    public boolean test(final Lifetime l1, final Lifetime l2) {
        if (l1 == null || l2 == null) return false;
        return testIntervalPair(l1.getStartMillis(), l1.getEndMillis(), l2.getStartMillis(), l2.getEndMillis());
    }

    /**
     * Evaluates this temporal relation under existential sub-interval semantics ({@link Quantifier#ANY}):
     * returns {@code true} iff there exists at least one sub-interval {@code x_i} in {@code val1} and
     * at least one sub-interval {@code y_j} in {@code val2} such that {@code testIntervalPair(x_i, y_j)} holds.
     */
    public boolean testAny(final Object val1, final Object val2) {
        if (this == generalized) {
            return this.test(val1, val2);
        }
        final Lifetime l1 = extractLifetime(val1);
        final Lifetime l2 = extractLifetime(val2);
        return testAny(l1, l2);
    }

    public boolean testAny(final Lifetime l1, final Lifetime l2) {
        if (this == generalized) {
            return this.test(l1, l2);
        }
        if (l1 == null || l2 == null || l1.isEmpty() || l2.isEmpty()) return false;
        final long[] r1 = l1.getRawIntervals();
        final long[] r2 = l2.getRawIntervals();
        final int n1 = r1.length >>> 1;
        final int n2 = r2.length >>> 1;
        if (n1 == 1 && n2 == 1) {
            return testIntervalPair(r1[0], r1[1], r2[0], r2[1]);
        }
        switch (this) {
            case intersects:
                return l1.intersects(l2);
            case precedes:
                return r1[1] < r2[(n2 - 1) << 1];
            case succeeds:
                return r1[(n1 - 1) << 1] > r2[1];
            case meets: {
                if (r1[r1.length - 1] < r2[0] || r1[1] > r2[(n2 - 1) << 1]) return false;
                int i = 0, j = 0;
                while (i < n1 && j < n2) {
                    final long xEnd = r1[(i << 1) + 1];
                    final long yStart = r2[j << 1];
                    if (xEnd == yStart) return true;
                    if (xEnd < yStart) i++;
                    else j++;
                }
                return false;
            }
            case metBy:
                return meets.testAny(l2, l1);
            case disjoint: {
                for (int i = 0; i < n1; i++) {
                    final long xS = r1[i << 1];
                    final long xE = r1[(i << 1) + 1];
                    for (int j = 0; j < n2; j++) {
                        if (!intersectsRange(xS, xE, r2[j << 1], r2[(j << 1) + 1])) return true;
                    }
                }
                return false;
            }
            default: {
                // O(1) outer bounding-box fast-reject for all 9 overlapping/containment relations
                if (r1[r1.length - 1] < r2[0] || r1[0] > r2[r2.length - 1]) return false;
                for (int i = 0; i < n1; i++) {
                    final long xStart = r1[i << 1];
                    final long xEnd = r1[(i << 1) + 1];
                    int low = 0, high = n2 - 1, startIdx = n2;
                    while (low <= high) {
                        final int mid = (low + high) >>> 1;
                        if (r2[(mid << 1) + 1] >= xStart) {
                            startIdx = mid;
                            high = mid - 1;
                        } else {
                            low = mid + 1;
                        }
                    }
                    for (int j = startIdx; j < n2; j++) {
                        final long yStart = r2[j << 1];
                        final long yEnd = r2[(j << 1) + 1];
                        if (yStart > xEnd) break;
                        if (testIntervalPair(xStart, xEnd, yStart, yEnd)) return true;
                    }
                }
                return false;
            }
        }
    }

    /**
     * Evaluates this temporal relation using the specified {@link Quantifier} ({@link Quantifier#ALL} or {@link Quantifier#ANY}).
     */
    public boolean test(final Object val1, final Object val2, final Quantifier quantifier) {
        return quantifier == Quantifier.ANY ? testAny(val1, val2) : test(val1, val2);
    }

    public boolean test(final Lifetime l1, final Lifetime l2, final Quantifier quantifier) {
        if (l1 == null || l2 == null) return false;
        return quantifier == Quantifier.ANY ? testAny(l1, l2) : test(l1, l2);
    }

    /**
     * Returns the algebraic transpose (converse) {@code \pi^t} of this temporal operator (Section 4.1 of Ligozat AAAI-91),
     * such that {@code op.test(x, y) == op.transpose().test(y, x)}.
     */
    public Temporal transpose() {
        switch (this) {
            case intersects:      return intersects;
            case disjoint:        return disjoint;
            case overlaps:        return overlappedBy;
            case overlappedBy:    return overlaps;
            case precedes:        return succeeds;
            case succeeds:        return precedes;
            case meets:           return metBy;
            case metBy:           return meets;
            case during:          return contains;
            case contains:        return during;
            case eq:              return eq;
            case starts:          return startedBy;
            case startedBy:       return starts;
            case finishes:        return finishedBy;
            case finishedBy:      return finishes;
            case generalized:     return generalized;
            default:              throw new IllegalStateException("Unknown Temporal operator: " + this);
        }
    }

    /**
     * Converts this {@link Temporal} operator into its corresponding Ligozat convex lattice interval
     * {@code [\alpha, \beta]} in {@code \Pi(2, 2)}, or {@code null} if this operator is not a single convex interval in {@code \Pi(2, 2)}.
     */
    public GeneralizedIntervalRelation toLatticeInterval() {
        switch (this) {
            case precedes:        return GeneralizedIntervalRelation.singleton(GeneralizedRelation.ALLEN_PRECEDES);
            case meets:           return GeneralizedIntervalRelation.singleton(GeneralizedRelation.ALLEN_MEETS);
            case overlaps:        return GeneralizedIntervalRelation.singleton(GeneralizedRelation.ALLEN_OVERLAPS);
            case finishedBy:      return GeneralizedIntervalRelation.singleton(GeneralizedRelation.ALLEN_FINISHED_BY);
            case contains:        return GeneralizedIntervalRelation.singleton(GeneralizedRelation.ALLEN_CONTAINS);
            case starts:          return GeneralizedIntervalRelation.singleton(GeneralizedRelation.ALLEN_STARTS);
            case eq:              return GeneralizedIntervalRelation.singleton(GeneralizedRelation.ALLEN_EQUALS);
            case startedBy:       return GeneralizedIntervalRelation.singleton(GeneralizedRelation.ALLEN_STARTED_BY);
            case during:          return GeneralizedIntervalRelation.singleton(GeneralizedRelation.ALLEN_DURING);
            case finishes:        return GeneralizedIntervalRelation.singleton(GeneralizedRelation.ALLEN_FINISHES);
            case overlappedBy:    return GeneralizedIntervalRelation.singleton(GeneralizedRelation.ALLEN_OVERLAPPED_BY);
            case metBy:           return GeneralizedIntervalRelation.singleton(GeneralizedRelation.ALLEN_MET_BY);
            case succeeds:        return GeneralizedIntervalRelation.singleton(GeneralizedRelation.ALLEN_SUCCEEDS);
            case intersects:      return GeneralizedIntervalRelation.ALLEN_INTERSECTS;
            default:              return null;
        }
    }

    /**
     * Maps a Ligozat lattice interval {@code [\alpha, \beta]} in {@code \Pi(2, 2)} back to a named {@link Temporal}
     * operator if an exact match exists, or {@code null} otherwise.
     */
    public static Temporal fromLatticeInterval(final GeneralizedIntervalRelation interval) {
        if (interval == null || interval.isEmpty() || interval.getP() != 2 || interval.getQ() != 2) {
            return null;
        }
        if (interval.isSingleton()) {
            return interval.getLower().toAllenTemporal();
        }
        if (GeneralizedIntervalRelation.ALLEN_INTERSECTS.equals(interval)) return intersects;
        return null;
    }

    /**
     * Computes the algebraic composition {@code (this ; other)} in {@code \Pi(2, 2)} using Ligozat's Theorem 4.2:
     * {@code [\alpha, \beta] ; [\gamma, \delta] = [\inf(\alpha ; \gamma), \sup(\beta ; \delta)]}.
     */
    public GeneralizedIntervalRelation compose(final Temporal other) {
        final GeneralizedIntervalRelation left = this.toLatticeInterval();
        final GeneralizedIntervalRelation right = other == null ? null : other.toLatticeInterval();
        if (left == null || right == null) {
            throw new IllegalArgumentException("Both Temporal operators must represent convex lattice intervals in Pi(2, 2)");
        }
        return left.compose(right);
    }

    /**
     * Interprets a value as a {@link Lifetime}. Supported values are a {@link Lifetime}, an {@link Lifetime.Interval}
     * (wrapped as a single-interval {@link Lifetime}), an {@link Element} (read from its lifetime properties),
     * a {@link Date} or {@link java.time.Instant} or {@link Number} (a point interval), and the persisted
     * {@code temporalIntervals} string form (so that {@code has("temporalIntervals", TemporalP.xxx(...))} works on
     * the raw property value).
     *
     * @return the lifetime, or {@code null} if the value cannot be interpreted as one
     */
    public static Lifetime extractLifetime(final Object val) {
        if (val instanceof Lifetime) {
            return (Lifetime) val;
        } else if (val instanceof Lifetime.Interval) {
            return Lifetime.from(java.util.Collections.singletonList((Lifetime.Interval) val));
        } else if (val instanceof Element) {
            return Lifetime.getLifetimeFromProperties((Element) val);
        } else if (val instanceof Date || val instanceof java.time.Instant || val instanceof Number) {
            return Lifetime.from(val, val);
        } else if (val instanceof String) {
            try {
                return Lifetime.fromTemporalIntervals((String) val);
            } catch (final IllegalArgumentException e) {
                try {
                    return Lifetime.from(val, val);
                } catch (final IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
