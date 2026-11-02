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

import org.apache.tinkerpop.gremlin.structure.temporal.GeneralizedIntervalRelation;
import org.apache.tinkerpop.gremlin.structure.temporal.GeneralizedRelation;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;

import java.util.List;
import java.util.Objects;

/**
 * Predefined {@code Predicate} values that can be used as temporal filters in pattern matching,
 * supporting Allen's 13 interval primitives, multi-interval sweeps ({@link Temporal.Quantifier#ALL} and
 * {@link Temporal.Quantifier#ANY}), and Gérard Ligozat's (AAAI-91) generalized {@code (p, q)}-relations
 * and convex lattice intervals {@code [\alpha, \beta]}.
 */
public class TemporalP extends P<Object> {

    private GeneralizedRelation generalizedRelation;
    private GeneralizedIntervalRelation generalizedIntervalRelation;
    private Temporal.Quantifier quantifier = Temporal.Quantifier.ALL;

    public TemporalP(final PBiPredicate<Object, Object> biPredicate, final Object value) {
        super(biPredicate, value);
        if (biPredicate == Temporal.generalized && value instanceof List) {
            final List<?> pair = (List<?>) value;
            if (pair.size() == 2) {
                final Object target = pair.get(0);
                final Object spec = pair.get(1);
                if (spec instanceof GeneralizedRelation) {
                    this.value = target;
                    this.generalizedRelation = (GeneralizedRelation) spec;
                } else if (spec instanceof GeneralizedIntervalRelation) {
                    this.value = target;
                    this.generalizedIntervalRelation = (GeneralizedIntervalRelation) spec;
                }
            }
        }
    }

    private TemporalP(final Object value, final GeneralizedRelation relation) {
        super(Temporal.generalized, value);
        this.generalizedRelation = Objects.requireNonNull(relation, "relation cannot be null");
    }

    private TemporalP(final Object value, final GeneralizedIntervalRelation intervalRelation) {
        super(Temporal.generalized, value);
        this.generalizedIntervalRelation = Objects.requireNonNull(intervalRelation, "intervalRelation cannot be null");
    }

    public GeneralizedRelation getGeneralizedRelation() {
        return generalizedRelation;
    }

    public GeneralizedIntervalRelation getGeneralizedIntervalRelation() {
        return generalizedIntervalRelation;
    }

    public Temporal.Quantifier getQuantifier() {
        return quantifier;
    }

    /**
     * Configures this predicate to evaluate existential sub-interval semantics ({@link Temporal.Quantifier#ANY}):
     * returns {@code true} if at least one sub-interval of the left operand and at least one sub-interval of
     * the right operand satisfy the relation.
     */
    public TemporalP any() {
        this.quantifier = Temporal.Quantifier.ANY;
        return this;
    }

    /**
     * Configures this predicate to evaluate holistic whole-lifetime semantics ({@link Temporal.Quantifier#ALL}, the default).
     */
    public TemporalP all() {
        this.quantifier = Temporal.Quantifier.ALL;
        return this;
    }

    @Override
    public boolean test(final Object testValue) {
        if (this.biPredicate == Temporal.generalized) {
            if (this.generalizedRelation != null) {
                return this.generalizedRelation.test(testValue, this.value);
            }
            if (this.generalizedIntervalRelation != null) {
                if (this.quantifier == Temporal.Quantifier.ANY
                        && this.generalizedIntervalRelation.getP() == 2
                        && this.generalizedIntervalRelation.getQ() == 2) {
                    final Lifetime l1 = Temporal.extractLifetime(testValue);
                    final Lifetime l2 = Temporal.extractLifetime(this.value);
                    if (l1 == null || l2 == null || l1.isEmpty() || l2.isEmpty()) return false;
                    final long[] r1 = l1.getRawIntervals();
                    final long[] r2 = l2.getRawIntervals();
                    final int n1 = r1.length >>> 1;
                    final int n2 = r2.length >>> 1;
                    for (int i = 0; i < n1; i++) {
                        final long xS = r1[i << 1];
                        final long xE = r1[(i << 1) + 1];
                        for (int j = 0; j < n2; j++) {
                            if (this.generalizedIntervalRelation.testIntervalPair(xS, xE, r2[j << 1], r2[(j << 1) + 1])) {
                                return true;
                            }
                        }
                    }
                    return false;
                }
                return this.generalizedIntervalRelation.test(testValue, this.value);
            }
        }
        if (this.quantifier == Temporal.Quantifier.ANY && this.biPredicate instanceof Temporal) {
            return ((Temporal) this.biPredicate).testAny(testValue, this.value);
        }
        return super.test(testValue);
    }

    public static TemporalP intersects(final Object value) { return new TemporalP(Temporal.intersects, value); }
    public static TemporalP disjoint(final Object value) { return new TemporalP(Temporal.disjoint, value); }
    public static TemporalP overlaps(final Object value) { return new TemporalP(Temporal.overlaps, value); }
    public static TemporalP precedes(final Object value) { return new TemporalP(Temporal.precedes, value); }
    public static TemporalP succeeds(final Object value) { return new TemporalP(Temporal.succeeds, value); }
    public static TemporalP overlappedBy(final Object value) { return new TemporalP(Temporal.overlappedBy, value); }
    public static TemporalP meets(final Object value) { return new TemporalP(Temporal.meets, value); }
    public static TemporalP metBy(final Object value) { return new TemporalP(Temporal.metBy, value); }
    public static TemporalP during(final Object value) { return new TemporalP(Temporal.during, value); }
    public static TemporalP contains(final Object value) { return new TemporalP(Temporal.contains, value); }
    public static TemporalP eq(final Object value) { return new TemporalP(Temporal.eq, value); }
    public static TemporalP starts(final Object value) { return new TemporalP(Temporal.starts, value); }
    public static TemporalP startedBy(final Object value) { return new TemporalP(Temporal.startedBy, value); }
    public static TemporalP finishes(final Object value) { return new TemporalP(Temporal.finishes, value); }
    public static TemporalP finishedBy(final Object value) { return new TemporalP(Temporal.finishedBy, value); }

    /**
     * Creates a {@link TemporalP} for a Ligozat convex lattice interval {@code [\alpha, \beta]} in {@code \Pi(2, 2)}
     * bounded by two primitive Allen {@link Temporal} operators {@code lower <= upper}.
     * For example:
     * <ul>
     *     <li>{@code TemporalP.between(Temporal.precedes, Temporal.meets, target)} represents {@code [(0,0), (0,1)]} ({@code precedes} or {@code meets})</li>
     *     <li>{@code TemporalP.between(Temporal.starts, Temporal.finishes, target)} represents {@code [(1,2), (2,3)]} ({@code enclosedBy})</li>
     * </ul>
     */
    public static TemporalP between(final Temporal lower, final Temporal upper, final Object value) {
        final GeneralizedRelation lowerRel = GeneralizedRelation.fromAllenTemporal(Objects.requireNonNull(lower, "lower cannot be null"));
        final GeneralizedRelation upperRel = GeneralizedRelation.fromAllenTemporal(Objects.requireNonNull(upper, "upper cannot be null"));
        return new TemporalP(value, new GeneralizedIntervalRelation(lowerRel, upperRel));
    }

    /**
     * Creates a {@link TemporalP} for an exact Ligozat {@code (p, q)}-relation {@code \pi in \Pi(p, q)}.
     */
    public static TemporalP generalized(final Object value, final GeneralizedRelation relation) {
        return new TemporalP(value, relation);
    }

    /**
     * Creates a {@link TemporalP} for a Ligozat convex lattice interval {@code [\alpha, \beta]} in {@code \Pi(p, q)}.
     */
    public static TemporalP generalized(final Object value, final GeneralizedIntervalRelation intervalRelation) {
        return new TemporalP(value, intervalRelation);
    }

    public static TemporalP generalized(final Object value) {
        return new TemporalP(Temporal.generalized, value);
    }

    /**
     * Creates a {@link TemporalP} for the given {@link Temporal} operator evaluated under existential
     * sub-interval semantics ({@link Temporal.Quantifier#ANY}).
     */
    public static TemporalP any(final Temporal op, final Object value) {
        return new TemporalP(Objects.requireNonNull(op, "op cannot be null"), value).any();
    }

    /**
     * Creates a {@link TemporalP} for the given {@link Temporal} operator evaluated under holistic
     * whole-lifetime semantics ({@link Temporal.Quantifier#ALL}).
     */
    public static TemporalP all(final Temporal op, final Object value) {
        return new TemporalP(Objects.requireNonNull(op, "op cannot be null"), value).all();
    }

    /**
     * Returns the algebraic transpose (converse) of this {@link TemporalP} predicate, preserving its {@link Temporal.Quantifier}.
     */
    public TemporalP transpose() {
        final TemporalP transposed;
        if (this.biPredicate == Temporal.generalized) {
            if (this.generalizedRelation != null) {
                transposed = generalized(this.value, this.generalizedRelation.transpose());
            } else if (this.generalizedIntervalRelation != null) {
                transposed = generalized(this.value, this.generalizedIntervalRelation.transpose());
            } else {
                transposed = this.clone();
            }
        } else if (this.biPredicate instanceof Temporal) {
            transposed = new TemporalP(((Temporal) this.biPredicate).transpose(), this.value);
        } else {
            transposed = this.clone();
        }
        transposed.quantifier = this.quantifier;
        return transposed;
    }

    @Override
    public boolean equals(final Object other) {
        if (!super.equals(other)) return false;
        final TemporalP that = (TemporalP) other;
        return this.quantifier == that.quantifier
                && Objects.equals(this.generalizedRelation, that.generalizedRelation)
                && Objects.equals(this.generalizedIntervalRelation, that.generalizedIntervalRelation);
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ Objects.hash(this.quantifier, this.generalizedRelation, this.generalizedIntervalRelation);
    }

    @Override
    public String toString() {
        final String suffix = this.quantifier == Temporal.Quantifier.ANY ? ".any()" : "";
        if (this.biPredicate == Temporal.generalized) {
            if (this.generalizedRelation != null) {
                return "generalized(" + this.value + ", " + this.generalizedRelation + ")" + suffix;
            }
            if (this.generalizedIntervalRelation != null) {
                return "generalized(" + this.value + ", " + this.generalizedIntervalRelation + ")" + suffix;
            }
        }
        return super.toString() + suffix;
    }

    @Override
    public TemporalP clone() {
        final TemporalP clone = (TemporalP) super.clone();
        clone.generalizedRelation = this.generalizedRelation;
        clone.generalizedIntervalRelation = this.generalizedIntervalRelation;
        clone.quantifier = this.quantifier;
        return clone;
    }
}
