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
package org.apache.tinkerpop.gremlin.structure.temporal;

import org.apache.tinkerpop.gremlin.process.traversal.Temporal;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Implements Gérard Ligozat's (AAAI-91) generalized interval calculus {@code \Pi(p, q)} of {@code (p, q)}-relations
 * and its distributive lattice structure {@code (\Pi(p, q), <=)}.
 * <p/>
 * A generalized {@code n}-interval in a linear order {@code T} is a strictly increasing sequence of {@code n} points
 * {@code x = (x_1 < x_2 < ... < x_n)}. A {@code q}-interval {@code y = (y_1, ..., y_q)} partitions {@code T} into
 * {@code 2q + 1} zones numbered {@code 0} to {@code 2q}:
 * <ul>
 *     <li>Zone {@code 0} is {@code {t in T | t < y_1}}</li>
 *     <li>Zone {@code 2s - 1} (odd, {@code 1 <= s <= q}) is the singleton boundary point {@code {y_s}}</li>
 *     <li>Zone {@code 2s} (even, {@code 1 <= s < q}) is the open interval {@code {t in T | y_s < t < y_{s+1}}}</li>
 *     <li>Zone {@code 2q} is {@code {t in T | t > y_q}}</li>
 * </ul>
 * The {@code (p, q)}-relation {@code \pi in \Pi(p, q)} of a {@code p}-interval {@code x} relative to a {@code q}-interval
 * {@code y} is a non-decreasing sequence of {@code p} integers in {@code [0, 2q]} where each odd integer occurs at most once.
 */
public final class GeneralizedRelation implements Serializable, Comparable<GeneralizedRelation> {

    private static final long serialVersionUID = 1L;

    private final int p;
    private final int q;
    private final int[] zones;

    // Pre-allocated Vilain \Pi(1, 2) point-to-interval relations (page 3, Example ii)
    public static final GeneralizedRelation POINT_BEFORE = new GeneralizedRelation(1, 2, 0);
    public static final GeneralizedRelation POINT_BEGINS = new GeneralizedRelation(1, 2, 1);
    public static final GeneralizedRelation POINT_DURING = new GeneralizedRelation(1, 2, 2);
    public static final GeneralizedRelation POINT_ENDS   = new GeneralizedRelation(1, 2, 3);
    public static final GeneralizedRelation POINT_AFTER  = new GeneralizedRelation(1, 2, 4);

    // Pre-allocated Vilain \Pi(2, 1) interval-to-point relations (page 3, Example ii)
    public static final GeneralizedRelation INTERVAL_BEFORE   = new GeneralizedRelation(2, 1, 0, 0);
    public static final GeneralizedRelation INTERVAL_ENDED_BY = new GeneralizedRelation(2, 1, 0, 1);
    public static final GeneralizedRelation INTERVAL_CONTAINS = new GeneralizedRelation(2, 1, 0, 2);
    public static final GeneralizedRelation INTERVAL_BEGUN_BY = new GeneralizedRelation(2, 1, 1, 2);
    public static final GeneralizedRelation INTERVAL_AFTER    = new GeneralizedRelation(2, 1, 2, 2);

    // Pre-allocated Allen \Pi(2, 2) interval-to-interval relations (page 3, Example iii & Fig. 6 H(2,2))
    public static final GeneralizedRelation ALLEN_PRECEDES      = new GeneralizedRelation(2, 2, 0, 0);
    public static final GeneralizedRelation ALLEN_MEETS         = new GeneralizedRelation(2, 2, 0, 1);
    public static final GeneralizedRelation ALLEN_OVERLAPS      = new GeneralizedRelation(2, 2, 0, 2);
    public static final GeneralizedRelation ALLEN_FINISHED_BY   = new GeneralizedRelation(2, 2, 0, 3);
    public static final GeneralizedRelation ALLEN_CONTAINS      = new GeneralizedRelation(2, 2, 0, 4);
    public static final GeneralizedRelation ALLEN_STARTS        = new GeneralizedRelation(2, 2, 1, 2);
    public static final GeneralizedRelation ALLEN_EQUALS        = new GeneralizedRelation(2, 2, 1, 3);
    public static final GeneralizedRelation ALLEN_STARTED_BY    = new GeneralizedRelation(2, 2, 1, 4);
    public static final GeneralizedRelation ALLEN_DURING        = new GeneralizedRelation(2, 2, 2, 2);
    public static final GeneralizedRelation ALLEN_FINISHES      = new GeneralizedRelation(2, 2, 2, 3);
    public static final GeneralizedRelation ALLEN_OVERLAPPED_BY = new GeneralizedRelation(2, 2, 2, 4);
    public static final GeneralizedRelation ALLEN_MET_BY        = new GeneralizedRelation(2, 2, 3, 4);
    public static final GeneralizedRelation ALLEN_SUCCEEDS      = new GeneralizedRelation(2, 2, 4, 4);

    /**
     * Constructs a {@code (p, q)}-relation in {@code \Pi(p, q)}.
     *
     * @param p     the arity of the first generalized interval ({@code p >= 1})
     * @param q     the arity of the second generalized interval ({@code q >= 1})
     * @param zones the {@code p} zone indices in {@code [0, 2q]}
     */
    public GeneralizedRelation(final int p, final int q, final int... zones) {
        if (p < 1) {
            throw new IllegalArgumentException("Arity p must be >= 1, got: " + p);
        }
        if (q < 1) {
            throw new IllegalArgumentException("Arity q must be >= 1, got: " + q);
        }
        Objects.requireNonNull(zones, "zones cannot be null");
        if (zones.length != p) {
            throw new IllegalArgumentException("Expected " + p + " zone values, got: " + zones.length);
        }
        final int maxZone = 2 * q;
        for (int i = 0; i < p; i++) {
            final int z = zones[i];
            if (z < 0 || z > maxZone) {
                throw new IllegalArgumentException("Zone index out of bounds [0, " + maxZone + "]: " + z);
            }
            if (i > 0) {
                final int prev = zones[i - 1];
                if (z < prev) {
                    throw new IllegalArgumentException("Zones must be non-decreasing: " + Arrays.toString(zones));
                }
                if ((z & 1) == 1 && z == prev) {
                    throw new IllegalArgumentException("Odd zone " + z + " may occur at most once in a (p, q)-relation: "
                            + Arrays.toString(zones));
                }
            }
        }
        this.p = p;
        this.q = q;
        this.zones = zones.clone();
    }

    public static GeneralizedRelation of(final int q, final int... zones) {
        Objects.requireNonNull(zones, "zones cannot be null");
        return new GeneralizedRelation(zones.length, q, zones);
    }

    /**
     * Returns the equality unit element {@code 1'_{p,p}} in {@code \Pi(p, p)} defined in Section 5.1 of Ligozat (AAAI-91),
     * where {@code 1'_{p,p}(i) = 2i - 1} for {@code 1 <= i <= p}.
     */
    public static GeneralizedRelation identity(final int p) {
        if (p < 1) {
            throw new IllegalArgumentException("Arity p must be >= 1, got: " + p);
        }
        final int[] idZones = new int[p];
        for (int i = 0; i < p; i++) {
            idZones[i] = 2 * i + 1;
        }
        return new GeneralizedRelation(p, p, idZones);
    }

    /**
     * Enumerates all elements of {@code \Pi(p, q)} in lexicographical order.
     */
    public static List<GeneralizedRelation> allRelations(final int p, final int q) {
        if (p < 1 || q < 1) {
            throw new IllegalArgumentException("Arities p and q must be >= 1");
        }
        final List<GeneralizedRelation> result = new ArrayList<>();
        final int[] current = new int[p];
        enumerateRecursive(p, q, 0, 0, current, result);
        return Collections.unmodifiableList(result);
    }

    private static void enumerateRecursive(final int p, final int q, final int idx, final int minZone,
                                            final int[] current, final List<GeneralizedRelation> result) {
        if (idx == p) {
            result.add(new GeneralizedRelation(p, q, current));
            return;
        }
        final int maxZone = 2 * q;
        for (int z = minZone; z <= maxZone; z++) {
            current[idx] = z;
            final int nextMin = ((z & 1) == 1) ? z + 1 : z;
            enumerateRecursive(p, q, idx + 1, nextMin, current, result);
        }
    }

    /**
     * Extracts the strictly increasing boundary point sequence {@code (x_1 < x_2 < ... < x_p)} of a temporal object.
     * <ul>
     *     <li>A point interval {@code [t, t]} (or {@code Date}/{@code Instant}/{@code Number}) yields a 1-interval {@code (t)}.</li>
     *     <li>A single convex interval {@code [s, e)} with {@code s < e} yields a 2-interval {@code (s, e)}.</li>
     *     <li>A multi-interval {@link Lifetime} with {@code k} disjoint intervals yields a {@code 2k}-interval
     *     (or fewer if a component is a point interval).</li>
     * </ul>
     *
     * @return the strictly increasing array of boundary epoch milliseconds, or {@code null} if {@code obj} is not a valid non-empty temporal object
     */
    public static long[] extractPoints(final Object obj) {
        final Lifetime lifetime = Temporal.extractLifetime(obj);
        if (lifetime == null || lifetime.isEmpty()) {
            return null;
        }
        final long[] raw = lifetime.getRawIntervals();
        final int intervalCount = raw.length >>> 1;
        boolean hasPointInterval = false;
        for (int i = 0; i < intervalCount; i++) {
            if (raw[i << 1] == raw[(i << 1) + 1]) {
                hasPointInterval = true;
                break;
            }
        }
        if (!hasPointInterval) {
            return raw;
        }
        int count = 0;
        long prev = Long.MIN_VALUE;
        for (int i = 0; i < intervalCount; i++) {
            final long s = raw[i << 1];
            final long e = raw[(i << 1) + 1];
            if (count == 0 || s > prev) {
                count++;
                prev = s;
            }
            if (e > s) {
                count++;
                prev = e;
            }
        }
        final long[] points = new long[count];
        int idx = 0;
        for (int i = 0; i < intervalCount; i++) {
            final long s = raw[i << 1];
            final long e = raw[(i << 1) + 1];
            if (idx == 0 || s > points[idx - 1]) {
                points[idx++] = s;
            }
            if (e > s) {
                points[idx++] = e;
            }
        }
        return points;
    }

    /**
     * Computes the exact {@code (p, q)}-relation {@code \pi in \Pi(p, q)} of {@code x} relative to {@code y}
     * from their strictly increasing boundary point arrays {@code x = (x_1 < ... < x_p)} and {@code y = (y_1 < ... < y_q)}.
     */
    public static GeneralizedRelation betweenPoints(final long[] x, final long[] y) {
        Objects.requireNonNull(x, "x points cannot be null");
        Objects.requireNonNull(y, "y points cannot be null");
        if (x.length < 1 || y.length < 1) {
            throw new IllegalArgumentException("Point sequences must be non-empty");
        }
        validateStrictlyIncreasing(x, "x");
        validateStrictlyIncreasing(y, "y");

        final int p = x.length;
        final int q = y.length;
        final int[] zones = new int[p];
        for (int i = 0; i < p; i++) {
            zones[i] = computeZone(x[i], y);
        }
        return new GeneralizedRelation(p, q, zones);
    }

    /**
     * Computes the exact {@code (p, q)}-relation {@code \pi in \Pi(p, q)} between any two temporal objects
     * ({@link Lifetime.Interval}, {@link Lifetime}, {@link org.apache.tinkerpop.gremlin.structure.Element},
     * {@link java.util.Date}, {@link java.time.Instant}, {@link Number}, or ISO/interval {@link String}).
     *
     * @return the {@link GeneralizedRelation}, or {@code null} if either object has no valid temporal representation
     */
    public static GeneralizedRelation between(final Object val1, final Object val2) {
        final long[] x = extractPoints(val1);
        final long[] y = extractPoints(val2);
        if (x == null || y == null) {
            return null;
        }
        return betweenPoints(x, y);
    }

    private static void validateStrictlyIncreasing(final long[] pts, final String name) {
        for (int i = 1; i < pts.length; i++) {
            if (pts[i] <= pts[i - 1]) {
                throw new IllegalArgumentException("Points in " + name + " must be strictly increasing: " + Arrays.toString(pts));
            }
        }
    }

    static int computeZoneForPoint(final long t, final long[] y) {
        return computeZone(t, y);
    }

    /**
     * Binary search to locate which zone of {@code y = (y_1 < ... < y_q)} the point {@code t} belongs to.
     */
    private static int computeZone(final long t, final long[] y) {
        final int q = y.length;
        if (t < y[0]) {
            return 0;
        }
        if (t > y[q - 1]) {
            return 2 * q;
        }
        int low = 0;
        int high = q - 1;
        while (low <= high) {
            final int mid = (low + high) >>> 1;
            final long yMid = y[mid];
            if (t == yMid) {
                return 2 * mid + 1;
            } else if (t < yMid) {
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        // t lies strictly between y[high] and y[low], where low == high + 1
        return 2 * low;
    }

    public int getP() {
        return p;
    }

    public int getQ() {
        return q;
    }

    /**
     * Returns the zone index {@code \pi(i)} using 1-based indexing {@code (1 <= i <= p)} as in Ligozat's paper,
     * with the convention {@code \pi(0) = 0} and {@code \pi(i) = 2q} for {@code i > p} (Section 4.1).
     */
    public int getZone(final int oneBasedIndex) {
        if (oneBasedIndex <= 0) {
            return 0;
        }
        if (oneBasedIndex > p) {
            return 2 * q;
        }
        return zones[oneBasedIndex - 1];
    }

    public int[] getZones() {
        return zones.clone();
    }

    // =========================================================================
    // SECTION 3.1: DISTRIBUTIVE LATTICE ORDER STRUCTURE (\Pi(p, q), <=)
    // =========================================================================

    /**
     * Lattice partial order: {@code \pi <= \pi'} if and only if {@code \pi(i) <= \pi'(i)} for all {@code i = 1, ..., p}.
     */
    public boolean lessThanOrEqual(final GeneralizedRelation other) {
        checkSameArity(other);
        for (int i = 0; i < p; i++) {
            if (this.zones[i] > other.zones[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Greatest lower bound (meet / infimum) in the distributive lattice {@code (\Pi(p, q), <=)},
     * computed component-wise (Proposition 3.1).
     */
    public GeneralizedRelation inf(final GeneralizedRelation other) {
        checkSameArity(other);
        final int[] result = new int[p];
        for (int i = 0; i < p; i++) {
            result[i] = Math.min(this.zones[i], other.zones[i]);
        }
        return new GeneralizedRelation(p, q, result);
    }

    /**
     * Least upper bound (join / supremum) in the distributive lattice {@code (\Pi(p, q), <=)},
     * computed component-wise (Proposition 3.1).
     */
    public GeneralizedRelation sup(final GeneralizedRelation other) {
        checkSameArity(other);
        final int[] result = new int[p];
        for (int i = 0; i < p; i++) {
            result[i] = Math.max(this.zones[i], other.zones[i]);
        }
        return new GeneralizedRelation(p, q, result);
    }

    /**
     * Topological distance (L1 Manhattan distance in the Hasse diagram {@code H(p, q)} embedded in {@code R^p}).
     * Two relations are immediate neighbours in {@code H(p, q)} if and only if their topological distance is {@code 1}.
     */
    public int topologicalDistance(final GeneralizedRelation other) {
        checkSameArity(other);
        int dist = 0;
        for (int i = 0; i < p; i++) {
            dist += Math.abs(this.zones[i] - other.zones[i]);
        }
        return dist;
    }

    public boolean isImmediateNeighbour(final GeneralizedRelation other) {
        return p == other.p && q == other.q && topologicalDistance(other) == 1;
    }

    /**
     * Returns all immediate successors of {@code \pi} in the Hasse diagram {@code H(p, q)}.
     */
    public List<GeneralizedRelation> immediateSuccessors() {
        final List<GeneralizedRelation> successors = new ArrayList<>(p);
        final int maxZone = 2 * q;
        for (int i = 0; i < p; i++) {
            final int nextZone = zones[i] + 1;
            if (nextZone > maxZone) {
                continue;
            }
            if (i + 1 < p) {
                if (nextZone > zones[i + 1]) {
                    continue;
                }
                if ((nextZone & 1) == 1 && nextZone == zones[i + 1]) {
                    continue;
                }
            }
            final int[] nextZones = zones.clone();
            nextZones[i] = nextZone;
            successors.add(new GeneralizedRelation(p, q, nextZones));
        }
        return successors;
    }

    /**
     * Returns all immediate predecessors of {@code \pi} in the Hasse diagram {@code H(p, q)}.
     */
    public List<GeneralizedRelation> immediatePredecessors() {
        final List<GeneralizedRelation> predecessors = new ArrayList<>(p);
        for (int i = 0; i < p; i++) {
            final int prevZone = zones[i] - 1;
            if (prevZone < 0) {
                continue;
            }
            if (i > 0) {
                if (prevZone < zones[i - 1]) {
                    continue;
                }
                if ((prevZone & 1) == 1 && prevZone == zones[i - 1]) {
                    continue;
                }
            }
            final int[] prevZones = zones.clone();
            prevZones[i] = prevZone;
            predecessors.add(new GeneralizedRelation(p, q, prevZones));
        }
        return predecessors;
    }

    // =========================================================================
    // SECTION 4.1: OPERATIONS ON \Pi(p, q) — TRANSPOSITION & COMPOSITION
    // =========================================================================

    /**
     * Computes the transpose {@code \pi^t in \Pi(q, p)} defined in Section 4.1 of Ligozat (AAAI-91):
     * <pre>
     *   Consider the first q odd integers odd(i) = 2i - 1 for i = 1, ..., q,
     *   and position odd(i) in the sequence \pi(1), ..., \pi(p):
     *     if odd(i) < \pi(1),               then \pi^t(i) = 0;
     *     if \pi(p) < odd(i),               then \pi^t(i) = 2p;
     *     if odd(i) = \pi(j),               then \pi^t(i) = 2j - 1;
     *     if \pi(j) < odd(i) < \pi(j + 1),  then \pi^t(i) = 2j.
     * </pre>
     * By Proposition 4.1, transposition is an order-reversing involution from {@code \Pi(p, q)} onto {@code \Pi(q, p)}.
     */
    public GeneralizedRelation transpose() {
        final int[] transposedZones = new int[q];
        for (int i = 1; i <= q; i++) {
            final int oddI = 2 * i - 1;
            if (oddI < zones[0]) {
                transposedZones[i - 1] = 0;
            } else if (zones[p - 1] < oddI) {
                transposedZones[i - 1] = 2 * p;
            } else {
                int assigned = -1;
                for (int j = 1; j <= p; j++) {
                    final int piJ = zones[j - 1];
                    if (oddI == piJ) {
                        assigned = 2 * j - 1;
                        break;
                    } else if (j < p && piJ < oddI && oddI < zones[j]) {
                        assigned = 2 * j;
                        break;
                    }
                }
                transposedZones[i - 1] = assigned;
            }
        }
        return new GeneralizedRelation(q, p, transposedZones);
    }

    /**
     * Computes the composition {@code (\pi ; \pi')} in {@code \Pi(p, r)} of {@code \pi in \Pi(p, q)} and
     * {@code \pi' in \Pi(q, r)} using Ligozat's closed-form formula in Section 4.1:
     * <ul>
     *     <li>If {@code \pi(j)} is odd: {@code \pi''(j) = \pi'((\pi(j) + 1) / 2)}</li>
     *     <li>If {@code \pi(j)} is even: {@code \pi''(j) in [[\pi'(\pi(j) / 2), \pi'((\pi(j) + 2) / 2)]]}</li>
     * </ul>
     * where {@code [[m, n]]} leaves out odd endpoints: {@code [m + (m is odd ? 1 : 0), n - (n is odd ? 1 : 0)]},
     * with the convention {@code \pi'(0) = 0} and {@code \pi'(i) = 2r} for {@code i > q}.
     * <p/>
     * As remarked on page 5 of Ligozat (AAAI-91), {@code (\pi ; \pi')} is always a non-empty lattice interval in {@code \Pi(p, r)}.
     */
    public GeneralizedIntervalRelation compose(final GeneralizedRelation other) {
        Objects.requireNonNull(other, "other relation cannot be null");
        if (this.q != other.p) {
            return GeneralizedIntervalRelation.empty(this.p, other.q);
        }
        final int r = other.q;
        final int[] minZones = new int[p];
        final int[] maxZones = new int[p];

        for (int j = 1; j <= p; j++) {
            final int piJ = getZone(j);
            if ((piJ & 1) == 1) {
                final int val = other.getZone((piJ + 1) / 2);
                minZones[j - 1] = val;
                maxZones[j - 1] = val;
            } else {
                final int m = other.getZone(piJ / 2);
                final int n = other.getZone((piJ + 2) / 2);
                final int evenM = ((m & 1) == 1) ? (m + 1) : m;
                final int evenN = ((n & 1) == 1) ? (n - 1) : n;
                minZones[j - 1] = evenM;
                maxZones[j - 1] = evenN;
            }
        }
        return new GeneralizedIntervalRelation(
                new GeneralizedRelation(p, r, minZones),
                new GeneralizedRelation(p, r, maxZones));
    }

    // =========================================================================
    // SECTION 2.2: ASSOCIATED INEQUATIONS E(\pi)
    // =========================================================================

    /**
     * Returns the system of endpoint equations and inequations {@code E(\pi)} defined in Section 2.2 (Equation 1):
     * <ul>
     *     <li>{@code x_i = y_{(\pi(i)+1)/2}} if {@code \pi(i)} is odd</li>
     *     <li>{@code x_i > y_{\pi(i)/2}} if {@code \pi(i)} is even and {@code \pi(i) > 0}</li>
     *     <li>{@code x_i < y_{(\pi(i)+2)/2}} if {@code \pi(i)} is even and {@code \pi(i) < 2q}</li>
     * </ul>
     */
    public List<String> toInequations() {
        final List<String> inequations = new ArrayList<>();
        for (int i = 1; i <= p; i++) {
            final int pi = getZone(i);
            if ((pi & 1) == 1) {
                inequations.add("x" + i + " = y" + ((pi + 1) / 2));
            } else {
                if (pi > 0) {
                    inequations.add("x" + i + " > y" + (pi / 2));
                }
                if (pi < 2 * q) {
                    inequations.add("x" + i + " < y" + ((pi + 2) / 2));
                }
            }
        }
        return Collections.unmodifiableList(inequations);
    }

    /**
     * If this relation is in {@code \Pi(2, 2)}, returns the corresponding primitive {@link Temporal} Allen operator.
     *
     * @return the matching {@link Temporal} primitive, or {@code null} if {@code (p, q) != (2, 2)}
     */
    public Temporal toAllenTemporal() {
        if (p != 2 || q != 2) {
            return null;
        }
        final int z1 = zones[0];
        final int z2 = zones[1];
        if (z1 == 0 && z2 == 0) return Temporal.precedes;
        if (z1 == 0 && z2 == 1) return Temporal.meets;
        if (z1 == 0 && z2 == 2) return Temporal.overlaps;
        if (z1 == 0 && z2 == 3) return Temporal.finishedBy;
        if (z1 == 0 && z2 == 4) return Temporal.contains;
        if (z1 == 1 && z2 == 2) return Temporal.starts;
        if (z1 == 1 && z2 == 3) return Temporal.eq;
        if (z1 == 1 && z2 == 4) return Temporal.startedBy;
        if (z1 == 2 && z2 == 2) return Temporal.during;
        if (z1 == 2 && z2 == 3) return Temporal.finishes;
        if (z1 == 2 && z2 == 4) return Temporal.overlappedBy;
        if (z1 == 3 && z2 == 4) return Temporal.metBy;
        if (z1 == 4 && z2 == 4) return Temporal.succeeds;
        return null;
    }

    /**
     * Converts a primitive {@link Temporal} Allen operator into its {@code \Pi(2, 2)} {@link GeneralizedRelation}.
     */
    public static GeneralizedRelation fromAllenTemporal(final Temporal temporal) {
        Objects.requireNonNull(temporal, "temporal cannot be null");
        switch (temporal) {
            case precedes:     return ALLEN_PRECEDES;
            case meets:        return ALLEN_MEETS;
            case overlaps:     return ALLEN_OVERLAPS;
            case finishedBy:   return ALLEN_FINISHED_BY;
            case contains:     return ALLEN_CONTAINS;
            case starts:       return ALLEN_STARTS;
            case eq:           return ALLEN_EQUALS;
            case startedBy:    return ALLEN_STARTED_BY;
            case during:       return ALLEN_DURING;
            case finishes:     return ALLEN_FINISHES;
            case overlappedBy: return ALLEN_OVERLAPPED_BY;
            case metBy:        return ALLEN_MET_BY;
            case succeeds:     return ALLEN_SUCCEEDS;
            default:
                throw new IllegalArgumentException(temporal + " is a composite lattice interval, not a single (2, 2)-relation atom");
        }
    }

    /**
     * Evaluates whether the two temporal objects stand in this exact {@code (p, q)}-relation.
     */
    public boolean test(final Object val1, final Object val2) {
        final long[] x = extractPoints(val1);
        final long[] y = extractPoints(val2);
        return testPoints(x, y);
    }

    public boolean testPoints(final long[] x, final long[] y) {
        if (x == null || y == null || x.length != p || y.length != q) {
            return false;
        }
        for (int i = 0; i < p; i++) {
            if (computeZone(x[i], y) != this.zones[i]) {
                return false;
            }
        }
        return true;
    }

    public boolean testIntervalPair(final long xStart, final long xEnd, final long yStart, final long yEnd) {
        if (xStart > xEnd || yStart > yEnd) {
            return false;
        }
        final int xArity = (xStart == xEnd) ? 1 : 2;
        final int yArity = (yStart == yEnd) ? 1 : 2;
        if (this.p != xArity || this.q != yArity) {
            return false;
        }
        if (zoneInSubInterval(xStart, yStart, yEnd) != this.zones[0]) {
            return false;
        }
        return xArity == 1 || zoneInSubInterval(xEnd, yStart, yEnd) == this.zones[1];
    }

    static int zoneInSubInterval(final long pt, final long yStart, final long yEnd) {
        if (yStart == yEnd) {
            return pt < yStart ? 0 : (pt == yStart ? 1 : 2);
        }
        return pt < yStart ? 0 : (pt == yStart ? 1 : (pt < yEnd ? 2 : (pt == yEnd ? 3 : 4)));
    }

    private void checkSameArity(final GeneralizedRelation other) {
        Objects.requireNonNull(other, "other relation cannot be null");
        if (this.p != other.p || this.q != other.q) {
            throw new IllegalArgumentException("Incompatible relation arities: (" + this.p + ", " + this.q
                    + ") vs (" + other.p + ", " + other.q + ")");
        }
    }

    @Override
    public int compareTo(final GeneralizedRelation other) {
        checkSameArity(other);
        for (int i = 0; i < p; i++) {
            final int cmp = Integer.compare(this.zones[i], other.zones[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof GeneralizedRelation)) return false;
        final GeneralizedRelation that = (GeneralizedRelation) o;
        return p == that.p && q == that.q && Arrays.equals(zones, that.zones);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(p, q);
        result = 31 * result + Arrays.hashCode(zones);
        return result;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Pi(").append(p).append(",").append(q).append(")[");
        for (int i = 0; i < p; i++) {
            if (i > 0) sb.append(",");
            sb.append(zones[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
