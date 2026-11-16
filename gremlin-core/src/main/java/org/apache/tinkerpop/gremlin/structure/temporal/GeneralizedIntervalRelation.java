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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Implements a lattice interval (also known as a "convex relation") {@code I(\alpha, \beta) = [\alpha, \beta] =
 * {\pi in \Pi(p, q) | \alpha <= \pi <= \beta}} in Gérard Ligozat's (AAAI-91) distributive lattice {@code (\Pi(p, q), <=)}.
 * <p/>
 * By Proposition 3.2 and Section 4.2 of Ligozat (AAAI-91):
 * <ul>
 *     <li>Lattice intervals coincide exactly with subsets of {@code \Pi(p, q)} definable by conjunctions of endpoint
 *     formulas {@code x_k \psi y_s} where {@code \psi in {=, <, >, <=, >=}} (the 83 tractable convex relations in
 *     {@code \Pi(2, 2)} where Allen's constraint propagation algorithm is complete).</li>
 *     <li>The set of lattice intervals is closed (stable) under <b>intersection</b>, <b>transposition</b>, and
 *     <b>composition</b>:
 *     <ul>
 *         <li>Intersection: {@code [\alpha, \beta] \cap [\gamma, \delta] = [\sup(\alpha, \gamma), \inf(\beta, \delta)]}</li>
 *         <li>Transposition: {@code [\alpha, \beta]^t = [\beta^t, \alpha^t]}</li>
 *         <li>Composition (Theorem 4.2): {@code [\alpha, \beta] ; [\gamma, \delta] = [\inf(\alpha ; \gamma), \sup(\beta ; \delta)]}</li>
 *     </ul>
 *     </li>
 * </ul>
 */
public final class GeneralizedIntervalRelation implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int p;
    private final int q;
    private final GeneralizedRelation lower;
    private final GeneralizedRelation upper;
    private final boolean empty;

    // Notable convex lattice intervals in \Pi(2, 2)
    /** Universal interval in \Pi(2, 2): [(0,0), (4,4)] (all 13 Allen relations). */
    public static final GeneralizedIntervalRelation ALLEN_ALL =
            new GeneralizedIntervalRelation(GeneralizedRelation.ALLEN_PRECEDES, GeneralizedRelation.ALLEN_SUCCEEDS);

    /** Empty interval in \Pi(2, 2): \emptyset (unsatisfiable relation). */
    public static final GeneralizedIntervalRelation ALLEN_EMPTY = empty(2, 2);

    /** Intersects in \Pi(2, 2): [(0,2), (2,4)] (the 9 overlapping Allen relations: x1 < y2 and x2 > y1). */
    public static final GeneralizedIntervalRelation ALLEN_INTERSECTS =
            new GeneralizedIntervalRelation(GeneralizedRelation.ALLEN_OVERLAPS, GeneralizedRelation.ALLEN_OVERLAPPED_BY);

    /** Precedes or meets in \Pi(2, 2): [(0,0), (0,1)] (causal sequence: x2 <= y1). */
    public static final GeneralizedIntervalRelation ALLEN_PRECEDES_OR_MEETS =
            new GeneralizedIntervalRelation(GeneralizedRelation.ALLEN_PRECEDES, GeneralizedRelation.ALLEN_MEETS);

    /** Succeeds or metBy in \Pi(2, 2): [(3,4), (4,4)] (reverse causal sequence: x1 >= y2). */
    public static final GeneralizedIntervalRelation ALLEN_SUCCEEDS_OR_MET_BY =
            new GeneralizedIntervalRelation(GeneralizedRelation.ALLEN_MET_BY, GeneralizedRelation.ALLEN_SUCCEEDS);

    /** Enclosed by (during, starts, finishes, or equals) in \Pi(2, 2): [(1,2), (2,3)] (x1 >= y1 and x2 <= y2). */
    public static final GeneralizedIntervalRelation ALLEN_ENCLOSED_BY =
            new GeneralizedIntervalRelation(GeneralizedRelation.ALLEN_STARTS, GeneralizedRelation.ALLEN_FINISHES);

    /** Encloses (contains, startedBy, finishedBy, or equals) in \Pi(2, 2): [(0,3), (1,4)] (x1 <= y1 and x2 >= y2). */
    public static final GeneralizedIntervalRelation ALLEN_ENCLOSES =
            new GeneralizedIntervalRelation(GeneralizedRelation.ALLEN_FINISHED_BY, GeneralizedRelation.ALLEN_STARTED_BY);

    private GeneralizedIntervalRelation(final int p, final int q,
                                        final GeneralizedRelation lower,
                                        final GeneralizedRelation upper,
                                        final boolean empty) {
        if (p < 1 || q < 1) {
            throw new IllegalArgumentException("Arities p and q must be >= 1");
        }
        this.p = p;
        this.q = q;
        this.lower = lower;
        this.upper = upper;
        this.empty = empty;
    }

    /**
     * Constructs a non-empty lattice interval {@code [\alpha, \beta]} where {@code \alpha <= \beta} in {@code \Pi(p, q)}.
     * If {@code \alpha} is not {@code <= \beta} component-wise, throws {@link IllegalArgumentException} (use
     * {@link #ofOrEmpty(GeneralizedRelation, GeneralizedRelation)} to obtain an empty interval when {@code \alpha \not<= \beta}).
     */
    public GeneralizedIntervalRelation(final GeneralizedRelation lower, final GeneralizedRelation upper) {
        Objects.requireNonNull(lower, "lower bound relation cannot be null");
        Objects.requireNonNull(upper, "upper bound relation cannot be null");
        if (lower.getP() != upper.getP() || lower.getQ() != upper.getQ()) {
            throw new IllegalArgumentException("Lower and upper bounds must belong to the same Pi(p, q): "
                    + lower + " vs " + upper);
        }
        if (!lower.lessThanOrEqual(upper)) {
            throw new IllegalArgumentException("Lower bound must be <= upper bound in lattice order: "
                    + lower + " vs " + upper);
        }
        this.p = lower.getP();
        this.q = lower.getQ();
        this.lower = lower;
        this.upper = upper;
        this.empty = false;
    }

    public static GeneralizedIntervalRelation singleton(final GeneralizedRelation relation) {
        Objects.requireNonNull(relation, "relation cannot be null");
        return new GeneralizedIntervalRelation(relation, relation);
    }

    public static GeneralizedIntervalRelation empty(final int p, final int q) {
        return new GeneralizedIntervalRelation(p, q, null, null, true);
    }

    public static GeneralizedIntervalRelation universal(final int p, final int q) {
        if (p < 1 || q < 1) {
            throw new IllegalArgumentException("Arities p and q must be >= 1");
        }
        final int[] minZones = new int[p];
        final int[] maxZones = new int[p];
        java.util.Arrays.fill(maxZones, 2 * q);
        return new GeneralizedIntervalRelation(
                new GeneralizedRelation(p, q, minZones),
                new GeneralizedRelation(p, q, maxZones));
    }

    /**
     * Creates the lattice interval {@code [\alpha, \beta]} if {@code \alpha <= \beta}, or the empty lattice interval
     * in {@code \Pi(p, q)} if {@code \alpha \not<= \beta}.
     */
    public static GeneralizedIntervalRelation ofOrEmpty(final GeneralizedRelation lower, final GeneralizedRelation upper) {
        Objects.requireNonNull(lower, "lower bound relation cannot be null");
        Objects.requireNonNull(upper, "upper bound relation cannot be null");
        if (lower.getP() != upper.getP() || lower.getQ() != upper.getQ()) {
            throw new IllegalArgumentException("Lower and upper bounds must belong to the same Pi(p, q): "
                    + lower + " vs " + upper);
        }
        if (!lower.lessThanOrEqual(upper)) {
            return empty(lower.getP(), lower.getQ());
        }
        return new GeneralizedIntervalRelation(lower, upper);
    }

    /**
     * Enumerates all non-empty lattice intervals ("convex relations") in {@code \Pi(p, q)}.
     * For {@code (p, q) = (2, 2)}, this yields the 82 non-empty convex relations (+ 1 empty = 83 total elements
     * noted on page 2 of Ligozat AAAI-91).
     */
    public static List<GeneralizedIntervalRelation> allNonEmptyIntervals(final int p, final int q) {
        final List<GeneralizedRelation> atoms = GeneralizedRelation.allRelations(p, q);
        final List<GeneralizedIntervalRelation> intervals = new ArrayList<>();
        for (int i = 0; i < atoms.size(); i++) {
            final GeneralizedRelation alpha = atoms.get(i);
            for (int j = i; j < atoms.size(); j++) {
                final GeneralizedRelation beta = atoms.get(j);
                if (alpha.lessThanOrEqual(beta)) {
                    intervals.add(new GeneralizedIntervalRelation(alpha, beta));
                }
            }
        }
        return Collections.unmodifiableList(intervals);
    }

    public int getP() {
        return p;
    }

    public int getQ() {
        return q;
    }

    public boolean isEmpty() {
        return empty;
    }

    public boolean isSingleton() {
        return !empty && lower.equals(upper);
    }

    public GeneralizedRelation getLower() {
        return lower;
    }

    public GeneralizedRelation getUpper() {
        return upper;
    }

    /**
     * Checks whether the atomic relation {@code \pi} belongs to this lattice interval {@code [\alpha, \beta]}.
     */
    public boolean contains(final GeneralizedRelation relation) {
        if (empty || relation == null || relation.getP() != p || relation.getQ() != q) {
            return false;
        }
        return lower.lessThanOrEqual(relation) && relation.lessThanOrEqual(upper);
    }

    /**
     * Checks whether {@code other} is a sub-interval of this lattice interval.
     */
    public boolean containsInterval(final GeneralizedIntervalRelation other) {
        Objects.requireNonNull(other, "other interval cannot be null");
        if (this.p != other.p || this.q != other.q) {
            throw new IllegalArgumentException("Incompatible arities");
        }
        if (other.empty) {
            return true;
        }
        if (this.empty) {
            return false;
        }
        return this.lower.lessThanOrEqual(other.lower) && other.upper.lessThanOrEqual(this.upper);
    }

    /**
     * Enumerates all atomic relations {@code \pi in \Pi(p, q)} contained in this lattice interval {@code [\alpha, \beta]}.
     */
    public List<GeneralizedRelation> toRelations() {
        if (empty) {
            return Collections.emptyList();
        }
        final List<GeneralizedRelation> result = new ArrayList<>();
        final int[] current = new int[p];
        enumerateIntervalRecursive(0, 0, current, result);
        return Collections.unmodifiableList(result);
    }

    private void enumerateIntervalRecursive(final int idx, final int prevConstraint,
                                            final int[] current, final List<GeneralizedRelation> result) {
        if (idx == p) {
            result.add(new GeneralizedRelation(p, q, current));
            return;
        }
        final int minZone = Math.max(prevConstraint, lower.getZone(idx + 1));
        final int maxZone = upper.getZone(idx + 1);
        for (int z = minZone; z <= maxZone; z++) {
            current[idx] = z;
            final int nextMin = ((z & 1) == 1) ? z + 1 : z;
            enumerateIntervalRecursive(idx + 1, nextMin, current, result);
        }
    }

    // =========================================================================
    // SECTION 4.2: STABILITY UNDER INTERSECTION, TRANSPOSITION, AND COMPOSITION
    // =========================================================================

    /**
     * Intersection of two lattice intervals in {@code \Pi(p, q)} (Proposition 4.2):
     * {@code [\alpha, \beta] \cap [\gamma, \delta] = [\sup(\alpha, \gamma), \inf(\beta, \delta)]}.
     */
    public GeneralizedIntervalRelation intersect(final GeneralizedIntervalRelation other) {
        Objects.requireNonNull(other, "other interval cannot be null");
        if (this.p != other.p || this.q != other.q) {
            throw new IllegalArgumentException("Cannot intersect lattice intervals of different arities: ("
                    + this.p + ", " + this.q + ") vs (" + other.p + ", " + other.q + ")");
        }
        if (this.empty || other.empty) {
            return empty(p, q);
        }
        final GeneralizedRelation newLower = this.lower.sup(other.lower);
        final GeneralizedRelation newUpper = this.upper.inf(other.upper);
        return ofOrEmpty(newLower, newUpper);
    }

    /**
     * Smallest lattice interval (convex hull / enclosure) containing both {@code this} and {@code other}:
     * {@code [\inf(\alpha, \gamma), \sup(\beta, \delta)]}.
     */
    public GeneralizedIntervalRelation hull(final GeneralizedIntervalRelation other) {
        Objects.requireNonNull(other, "other interval cannot be null");
        if (this.p != other.p || this.q != other.q) {
            throw new IllegalArgumentException("Cannot compute hull of lattice intervals with different arities");
        }
        if (this.empty) return other;
        if (other.empty) return this;
        return new GeneralizedIntervalRelation(this.lower.inf(other.lower), this.upper.sup(other.upper));
    }

    /**
     * Transposition of a lattice interval (Proposition 4.1 & 4.2):
     * Since transposition is an order-reversing bijection from {@code \Pi(p, q)} onto {@code \Pi(q, p)},
     * {@code [\alpha, \beta]^t = [\beta^t, \alpha^t]} in {@code \Pi(q, p)}.
     */
    public GeneralizedIntervalRelation transpose() {
        if (empty) {
            return empty(q, p);
        }
        return new GeneralizedIntervalRelation(upper.transpose(), lower.transpose());
    }

    /**
     * Composition of two lattice intervals (Theorem 4.2 of Ligozat AAAI-91):
     * <pre>
     *   ([\alpha, \beta] ; [\gamma, \delta]) = [ \inf(\alpha ; \gamma), \sup(\beta ; \delta) ]
     * </pre>
     * in {@code \Pi(p, r)}, where {@code [\alpha, \beta]} is in {@code \Pi(p, q)} and {@code [\gamma, \delta]} is in {@code \Pi(q, r)}.
     */
    public GeneralizedIntervalRelation compose(final GeneralizedIntervalRelation other) {
        Objects.requireNonNull(other, "other interval cannot be null");
        if (this.q != other.p) {
            return empty(this.p, other.q);
        }
        if (this.empty || other.empty) {
            return empty(this.p, other.q);
        }
        final GeneralizedIntervalRelation lowerComposed = this.lower.compose(other.lower);
        final GeneralizedIntervalRelation upperComposed = this.upper.compose(other.upper);
        if (lowerComposed.isEmpty() || upperComposed.isEmpty()) {
            return empty(this.p, other.q);
        }
        return ofOrEmpty(lowerComposed.getLower(), upperComposed.getUpper());
    }

    // =========================================================================
    // SECTION 3.2: CHARACTERIZING INTERVALS IN \Pi(p, q) VIA INEQUATIONS
    // =========================================================================

    /**
     * Translates this lattice interval {@code [\alpha, \beta]} into the equivalent conjunction of endpoint inequations
     * over {@code x_1, ..., x_p} and {@code y_1, ..., y_q} as proven in Section 3.2 of Ligozat (AAAI-91):
     * <pre>
     *   \pi(k) in [m, n] is equivalent to:
     *     y_{m/2}     <  x_k <  y_{(n+2)/2}   if m, n are even;
     *     y_{(m+1)/2} <= x_k <  y_{(n+2)/2}   if m is odd, n is even;
     *     y_{m/2}     <  x_k <= y_{(n+1)/2}   if m is even, n is odd;
     *     y_{(m+1)/2} <= x_k <= y_{(n+1)/2}   if m, n are odd;
     * </pre>
     * leaving out inequations involving {@code y_0} or {@code y_{q+1}}.
     */
    public List<String> toInequations() {
        if (empty) {
            return Collections.singletonList("false");
        }
        final List<String> formulas = new ArrayList<>();
        for (int k = 1; k <= p; k++) {
            final int m = lower.getZone(k);
            final int n = upper.getZone(k);
            if (m == n && (m & 1) == 1) {
                formulas.add("x" + k + " = y" + ((m + 1) / 2));
                continue;
            }
            if (m > 0) {
                if ((m & 1) == 1) {
                    formulas.add("x" + k + " >= y" + ((m + 1) / 2));
                } else {
                    formulas.add("x" + k + " > y" + (m / 2));
                }
            }
            if (n < 2 * q) {
                if ((n & 1) == 1) {
                    formulas.add("x" + k + " <= y" + ((n + 1) / 2));
                } else {
                    formulas.add("x" + k + " < y" + ((n + 2) / 2));
                }
            }
        }
        return Collections.unmodifiableList(formulas);
    }

    /**
     * Evaluates whether the {@code (p, q)}-relation between {@code val1} and {@code val2} falls within this lattice interval.
     */
    public boolean test(final Object val1, final Object val2) {
        if (empty) {
            return false;
        }
        final long[] x = GeneralizedRelation.extractPoints(val1);
        final long[] y = GeneralizedRelation.extractPoints(val2);
        return testPoints(x, y);
    }

    public boolean testPoints(final long[] x, final long[] y) {
        if (empty || x == null || y == null || x.length != p || y.length != q) {
            return false;
        }
        for (int i = 0; i < p; i++) {
            final int z = GeneralizedRelation.computeZoneForPoint(x[i], y);
            if (z < lower.getZone(i + 1) || z > upper.getZone(i + 1)) {
                return false;
            }
        }
        return true;
    }

    public boolean testIntervalPair(final long xStart, final long xEnd, final long yStart, final long yEnd) {
        if (empty || xStart > xEnd || yStart > yEnd) {
            return false;
        }
        final int xArity = (xStart == xEnd) ? 1 : 2;
        final int yArity = (yStart == yEnd) ? 1 : 2;
        if (p != xArity || q != yArity) {
            return false;
        }
        final int z1 = GeneralizedRelation.zoneInSubInterval(xStart, yStart, yEnd);
        if (z1 < lower.getZone(1) || z1 > upper.getZone(1)) {
            return false;
        }
        if (xArity == 1) {
            return true;
        }
        final int z2 = GeneralizedRelation.zoneInSubInterval(xEnd, yStart, yEnd);
        return z2 >= lower.getZone(2) && z2 <= upper.getZone(2);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof GeneralizedIntervalRelation)) return false;
        final GeneralizedIntervalRelation that = (GeneralizedIntervalRelation) o;
        return p == that.p && q == that.q && empty == that.empty
                && Objects.equals(lower, that.lower)
                && Objects.equals(upper, that.upper);
    }

    @Override
    public int hashCode() {
        return Objects.hash(p, q, empty, lower, upper);
    }

    @Override
    public String toString() {
        if (empty) {
            return "IntervalPi(" + p + "," + q + ")[EMPTY]";
        }
        return "IntervalPi(" + p + "," + q + ")[" + lower + " .. " + upper + "]";
    }
}
