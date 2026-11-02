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
import org.junit.Test;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GeneralizedRelationTest {

    @Test
    public void shouldEnumerateExactCardinalsFromLigozatPaper() {
        // Examples (i), (ii), (iii) on page 3 of Ligozat (AAAI-91):
        // \Pi(1, 1) has 3 elements: 0, 1, 2
        assertEquals(3, GeneralizedRelation.allRelations(1, 1).size());
        // \Pi(1, 2) has 5 elements: 0, 1, 2, 3, 4 (Vilain's point-to-interval relations)
        assertEquals(5, GeneralizedRelation.allRelations(1, 2).size());
        // \Pi(2, 1) has 5 elements: (0,0), (0,1), (0,2), (1,2), (2,2) (Vilain's interval-to-point relations)
        assertEquals(5, GeneralizedRelation.allRelations(2, 1).size());
        // \Pi(2, 2) has 13 elements: Allen's 13 primitive relations
        assertEquals(13, GeneralizedRelation.allRelations(2, 2).size());
        // Symmetry: |\Pi(p, q)| == |\Pi(q, p)|
        assertEquals(GeneralizedRelation.allRelations(2, 3).size(), GeneralizedRelation.allRelations(3, 2).size());
        assertEquals(GeneralizedRelation.allRelations(2, 4).size(), GeneralizedRelation.allRelations(4, 2).size());
    }

    @Test
    public void shouldValidateZoneConstraints() {
        // Valid non-decreasing with even duplicates allowed
        GeneralizedRelation rel = new GeneralizedRelation(3, 2, 0, 0, 2);
        assertArrayEquals(new int[]{0, 0, 2}, rel.getZones());

        // Decreasing zones must be rejected
        assertThrows(IllegalArgumentException.class, () -> new GeneralizedRelation(2, 2, 2, 1));

        // Duplicate odd zones must be rejected (odd zone is a single point, and x_1 < x_2)
        assertThrows(IllegalArgumentException.class, () -> new GeneralizedRelation(2, 2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GeneralizedRelation(2, 2, 3, 3));

        // Out of range [0, 2q] must be rejected
        assertThrows(IllegalArgumentException.class, () -> new GeneralizedRelation(2, 2, -1, 2));
        assertThrows(IllegalArgumentException.class, () -> new GeneralizedRelation(2, 2, 0, 5));
    }

    @Test
    public void shouldMapAll13AllenRelationsAndEvaluateCorrectly() {
        final Lifetime.Interval ref = new Lifetime.Interval(new Date(100), new Date(200));

        assertEquals(GeneralizedRelation.ALLEN_PRECEDES,
                GeneralizedRelation.between(new Lifetime.Interval(new Date(10), new Date(50)), ref));
        assertEquals(GeneralizedRelation.ALLEN_MEETS,
                GeneralizedRelation.between(new Lifetime.Interval(new Date(10), new Date(100)), ref));
        assertEquals(GeneralizedRelation.ALLEN_OVERLAPS,
                GeneralizedRelation.between(new Lifetime.Interval(new Date(50), new Date(150)), ref));
        assertEquals(GeneralizedRelation.ALLEN_FINISHED_BY,
                GeneralizedRelation.between(new Lifetime.Interval(new Date(50), new Date(200)), ref));
        assertEquals(GeneralizedRelation.ALLEN_CONTAINS,
                GeneralizedRelation.between(new Lifetime.Interval(new Date(50), new Date(250)), ref));
        assertEquals(GeneralizedRelation.ALLEN_STARTS,
                GeneralizedRelation.between(new Lifetime.Interval(new Date(100), new Date(150)), ref));
        assertEquals(GeneralizedRelation.ALLEN_EQUALS,
                GeneralizedRelation.between(new Lifetime.Interval(new Date(100), new Date(200)), ref));
        assertEquals(GeneralizedRelation.ALLEN_STARTED_BY,
                GeneralizedRelation.between(new Lifetime.Interval(new Date(100), new Date(250)), ref));
        assertEquals(GeneralizedRelation.ALLEN_DURING,
                GeneralizedRelation.between(new Lifetime.Interval(new Date(120), new Date(180)), ref));
        assertEquals(GeneralizedRelation.ALLEN_FINISHES,
                GeneralizedRelation.between(new Lifetime.Interval(new Date(120), new Date(200)), ref));
        assertEquals(GeneralizedRelation.ALLEN_OVERLAPPED_BY,
                GeneralizedRelation.between(new Lifetime.Interval(new Date(150), new Date(250)), ref));
        assertEquals(GeneralizedRelation.ALLEN_MET_BY,
                GeneralizedRelation.between(new Lifetime.Interval(new Date(200), new Date(250)), ref));
        assertEquals(GeneralizedRelation.ALLEN_SUCCEEDS,
                GeneralizedRelation.between(new Lifetime.Interval(new Date(250), new Date(300)), ref));

        for (final GeneralizedRelation allen : GeneralizedRelation.allRelations(2, 2)) {
            final Temporal t = allen.toAllenTemporal();
            assertNotNull(t);
            assertEquals(allen, GeneralizedRelation.fromAllenTemporal(t));
        }
    }

    @Test
    public void shouldSupportVilainPointToIntervalAndIntervalToPointRelations() {
        final Lifetime.Interval y = new Lifetime.Interval(new Date(100), new Date(200));

        // \Pi(1, 2): .before (0), .begins (1), .during (2), .ends (3), .after (4)
        assertEquals(GeneralizedRelation.POINT_BEFORE, GeneralizedRelation.between(new Date(50), y));
        assertEquals(GeneralizedRelation.POINT_BEGINS, GeneralizedRelation.between(new Date(100), y));
        assertEquals(GeneralizedRelation.POINT_DURING, GeneralizedRelation.between(new Date(150), y));
        assertEquals(GeneralizedRelation.POINT_ENDS,   GeneralizedRelation.between(new Date(200), y));
        assertEquals(GeneralizedRelation.POINT_AFTER,  GeneralizedRelation.between(new Date(250), y));

        // \Pi(2, 1): before. (0,0), ended-by. (0,1), contains. (0,2), begun-by. (1,2), after. (2,2)
        assertEquals(GeneralizedRelation.INTERVAL_BEFORE,   GeneralizedRelation.between(y, new Date(250)));
        assertEquals(GeneralizedRelation.INTERVAL_ENDED_BY, GeneralizedRelation.between(y, new Date(200)));
        assertEquals(GeneralizedRelation.INTERVAL_CONTAINS, GeneralizedRelation.between(y, new Date(150)));
        assertEquals(GeneralizedRelation.INTERVAL_BEGUN_BY, GeneralizedRelation.between(y, new Date(100)));
        assertEquals(GeneralizedRelation.INTERVAL_AFTER,    GeneralizedRelation.between(y, new Date(50)));
    }

    @Test
    public void shouldSupportMultiIntervalAndSingleIntervalGeneralizedRelations() {
        // Multi-interval o1 = [100, 200) U [300, 400) -> 4-interval (100, 200, 300, 400)
        // Zones of o1:
        //   0: (-inf, 100), 1: {100}, 2: (100, 200), 3: {200}, 4: (200, 300) [GAP],
        //   5: {300}, 6: (300, 400), 7: {400}, 8: (400, +inf)
        final Lifetime o1 = Lifetime.from(100L, 200L).addInterval(300L, 400L);

        // Single interval i1 = [220, 280) falling inside o1's gap (zone 4) -> Pi(2, 4)[4, 4]
        final Lifetime.Interval iInGap = new Lifetime.Interval(new Date(220), new Date(280));
        assertEquals(new GeneralizedRelation(2, 4, 4, 4), GeneralizedRelation.between(iInGap, o1));

        // Single interval i2 = [150, 350) starting in first interval (zone 2) and ending in second interval (zone 6) -> Pi(2, 4)[2, 6]
        final Lifetime.Interval iSpanning = new Lifetime.Interval(new Date(150), new Date(350));
        assertEquals(new GeneralizedRelation(2, 4, 2, 6), GeneralizedRelation.between(iSpanning, o1));
    }

    @Test
    public void shouldImplementDistributiveLatticeAndHasseDiagram() {
        // In H(2, 2) (Fig. 4 & Fig. 6): meets (0, 1) has immediate predecessor < (0, 0) and immediate successor overlaps (0, 2)
        assertEquals(Arrays.asList(GeneralizedRelation.ALLEN_PRECEDES),
                GeneralizedRelation.ALLEN_MEETS.immediatePredecessors());
        assertEquals(Arrays.asList(GeneralizedRelation.ALLEN_OVERLAPS),
                GeneralizedRelation.ALLEN_MEETS.immediateSuccessors());
        assertTrue(GeneralizedRelation.ALLEN_MEETS.isImmediateNeighbour(GeneralizedRelation.ALLEN_PRECEDES));
        assertTrue(GeneralizedRelation.ALLEN_MEETS.isImmediateNeighbour(GeneralizedRelation.ALLEN_OVERLAPS));

        // Lattice inf & sup on H(2, 2):
        // contains (0, 4) and starts (1, 2) -> inf is overlaps (0, 2), sup is startedBy (1, 4)
        assertEquals(GeneralizedRelation.ALLEN_OVERLAPS,
                GeneralizedRelation.ALLEN_CONTAINS.inf(GeneralizedRelation.ALLEN_STARTS));
        assertEquals(GeneralizedRelation.ALLEN_STARTED_BY,
                GeneralizedRelation.ALLEN_CONTAINS.sup(GeneralizedRelation.ALLEN_STARTS));

        // Verify distributive law: a.inf(b.sup(c)) == (a.inf(b)).sup(a.inf(c)) across all triples in \Pi(2, 2)
        final List<GeneralizedRelation> all22 = GeneralizedRelation.allRelations(2, 2);
        for (final GeneralizedRelation a : all22) {
            for (final GeneralizedRelation b : all22) {
                for (final GeneralizedRelation c : all22) {
                    assertEquals(a.inf(b.sup(c)), a.inf(b).sup(a.inf(c)));
                    assertEquals(a.sup(b.inf(c)), a.sup(b).inf(a.sup(c)));
                }
            }
        }
    }

    @Test
    public void shouldGenerateAssociatedInequationsFromSection2_2() {
        // overlaps = (0, 2): x1 < y1, x2 > y1, x2 < y2
        assertEquals(Arrays.asList("x1 < y1", "x2 > y1", "x2 < y2"),
                GeneralizedRelation.ALLEN_OVERLAPS.toInequations());

        // equals = (1, 3) = 1'_{2,2}: x1 = y1, x2 = y2
        assertEquals(GeneralizedRelation.identity(2), GeneralizedRelation.ALLEN_EQUALS);
        assertEquals(Arrays.asList("x1 = y1", "x2 = y2"),
                GeneralizedRelation.ALLEN_EQUALS.toInequations());
    }

    @Test
    public void shouldImplementLigozatTranspositionSection4_1() {
        // Proposition 4.1: Transposition is an order-reversing involution from \Pi(p, q) onto \Pi(q, p)
        // Example Fig. 7: \Pi(1, 2) <-> \Pi(2, 1)
        // 0 (.before) <-> (2, 2) (after.)
        // 1 (.begins) <-> (1, 2) (begun-by.)
        // 2 (.during) <-> (0, 2) (contains.)
        // 3 (.ends)   <-> (0, 1) (ended-by.)
        // 4 (.after)  <-> (0, 0) (before.)
        assertEquals(GeneralizedRelation.INTERVAL_AFTER,    GeneralizedRelation.POINT_BEFORE.transpose());
        assertEquals(GeneralizedRelation.INTERVAL_BEGUN_BY, GeneralizedRelation.POINT_BEGINS.transpose());
        assertEquals(GeneralizedRelation.INTERVAL_CONTAINS, GeneralizedRelation.POINT_DURING.transpose());
        assertEquals(GeneralizedRelation.INTERVAL_ENDED_BY, GeneralizedRelation.POINT_ENDS.transpose());
        assertEquals(GeneralizedRelation.INTERVAL_BEFORE,   GeneralizedRelation.POINT_AFTER.transpose());

        // Verify involution (\pi^t)^t == \pi and order-reversing (\pi <= \pi' <=> \pi'^t <= \pi^t) across \Pi(2, 3)
        final List<GeneralizedRelation> all23 = GeneralizedRelation.allRelations(2, 3);
        for (final GeneralizedRelation r1 : all23) {
            assertEquals(r1, r1.transpose().transpose());
            for (final GeneralizedRelation r2 : all23) {
                assertEquals(r1.lessThanOrEqual(r2), r2.transpose().lessThanOrEqual(r1.transpose()));
            }
        }

        // Verify Allen \Pi(2, 2) transposition matches Temporal.transpose()
        for (final GeneralizedRelation allen : GeneralizedRelation.allRelations(2, 2)) {
            final Temporal t = allen.toAllenTemporal();
            assertEquals(allen.transpose(), GeneralizedRelation.fromAllenTemporal(t.transpose()));
        }
    }

    @Test
    public void shouldImplementLigozatCompositionExamplesAndAlgebraAxioms() {
        // Section 4.1 Examples (page 4-5):
        // Example (i): \pi = \pi' = (0, 2) (overlaps); (\pi ; \pi') = {(0,0), (0,1), (0,2)} = {<, m, o}
        final GeneralizedIntervalRelation comp1 =
                GeneralizedRelation.ALLEN_OVERLAPS.compose(GeneralizedRelation.ALLEN_OVERLAPS);
        assertEquals(new GeneralizedIntervalRelation(
                GeneralizedRelation.ALLEN_PRECEDES, GeneralizedRelation.ALLEN_OVERLAPS), comp1);
        assertEquals(Arrays.asList(
                GeneralizedRelation.ALLEN_PRECEDES,
                GeneralizedRelation.ALLEN_MEETS,
                GeneralizedRelation.ALLEN_OVERLAPS), comp1.toRelations());

        // Example (ii): \pi = (2, 3) (ends/finishes), \pi' = (3, 4) (metBy); (\pi ; \pi') = {(4, 4)} = {>}
        final GeneralizedIntervalRelation comp2 =
                GeneralizedRelation.ALLEN_FINISHES.compose(GeneralizedRelation.ALLEN_MET_BY);
        assertEquals(GeneralizedIntervalRelation.singleton(GeneralizedRelation.ALLEN_SUCCEEDS), comp2);

        // Remark on page 5: 26 distinct intervals in Allen's 12x12 non-equality transition table (+ equality = 27 total in 13x13)
        final java.util.Set<GeneralizedIntervalRelation> transitionTableEntries = new java.util.HashSet<>();
        final java.util.Set<GeneralizedIntervalRelation> nonEqualityTableEntries = new java.util.HashSet<>();
        final List<GeneralizedRelation> all22 = GeneralizedRelation.allRelations(2, 2);
        for (final GeneralizedRelation r1 : all22) {
            for (final GeneralizedRelation r2 : all22) {
                final GeneralizedIntervalRelation entry = r1.compose(r2);
                assertFalse(entry.isEmpty());
                transitionTableEntries.add(entry);
                if (!r1.equals(GeneralizedRelation.ALLEN_EQUALS) && !r2.equals(GeneralizedRelation.ALLEN_EQUALS)) {
                    nonEqualityTableEntries.add(entry);
                }
                // Proposition 4.2: (\pi ; \pi')^t == (\pi'^t ; \pi^t)
                assertEquals(entry.transpose(), r2.transpose().compose(r1.transpose()));
            }
        }
        assertEquals(26, nonEqualityTableEntries.size());
        assertEquals(27, transitionTableEntries.size());

        // Section 5.1 Proposition (page 5): Identity 1'_{q,q} and Associativity ((r1 ; r2) ; r3) == (r1 ; (r2 ; r3))
        final GeneralizedRelation id2 = GeneralizedRelation.identity(2);
        for (final GeneralizedRelation r1 : all22) {
            assertEquals(GeneralizedIntervalRelation.singleton(r1), r1.compose(id2));
            assertEquals(GeneralizedIntervalRelation.singleton(r1), id2.compose(r1));
            assertTrue(r1.compose(r1.transpose()).contains(id2));
            assertTrue(r1.transpose().compose(r1).contains(id2));
        }

        for (final GeneralizedRelation r1 : all22) {
            for (final GeneralizedRelation r2 : all22) {
                for (final GeneralizedRelation r3 : all22) {
                    final GeneralizedIntervalRelation leftAssoc = r1.compose(r2).compose(GeneralizedIntervalRelation.singleton(r3));
                    final GeneralizedIntervalRelation rightAssoc = GeneralizedIntervalRelation.singleton(r1).compose(r2.compose(r3));
                    assertEquals(leftAssoc, rightAssoc);
                }
            }
        }
    }

    @Test
    public void shouldVerify83ConvexRelationsAndStabilityTheoremInSection4_2() {
        // Page 2 of Ligozat (AAAI-91): 83 convex relations in A(2) (82 non-empty lattice intervals + 1 empty)
        final List<GeneralizedIntervalRelation> nonEmptyIntervals =
                GeneralizedIntervalRelation.allNonEmptyIntervals(2, 2);
        assertEquals(82, nonEmptyIntervals.size());

        // Theorem 4.2: ([\alpha, \beta] ; [\gamma, \delta]) == [\inf(\alpha ; \gamma), \sup(\beta ; \delta)]
        // Verify that the interval composition formula matches the exact union of all pairwise (\pi ; \pi')
        for (int i = 0; i < nonEmptyIntervals.size(); i += 7) {
            final GeneralizedIntervalRelation iv1 = nonEmptyIntervals.get(i);
            for (int j = 0; j < nonEmptyIntervals.size(); j += 7) {
                final GeneralizedIntervalRelation iv2 = nonEmptyIntervals.get(j);
                final GeneralizedIntervalRelation composed = iv1.compose(iv2);

                final java.util.Set<GeneralizedRelation> union = new java.util.HashSet<>();
                for (final GeneralizedRelation p1 : iv1.toRelations()) {
                    for (final GeneralizedRelation p2 : iv2.toRelations()) {
                        union.addAll(p1.compose(p2).toRelations());
                    }
                }
                assertEquals(union, new java.util.HashSet<>(composed.toRelations()));
            }
        }

        // Verify inequation characterization of convex intervals (Section 3.2)
        // ALLEN_INTERSECTS = [(0,2), (2,4)] -> x1 < y2 and x2 > y1
        assertEquals(Arrays.asList("x1 < y2", "x2 > y1"),
                GeneralizedIntervalRelation.ALLEN_INTERSECTS.toInequations());
        // ALLEN_ENCLOSED_BY = [(1,2), (2,3)] -> x1 >= y1, x1 < y2, x2 > y1, x2 <= y2
        assertEquals(Arrays.asList("x1 >= y1", "x1 < y2", "x2 > y1", "x2 <= y2"),
                GeneralizedIntervalRelation.ALLEN_ENCLOSED_BY.toInequations());
    }
}
