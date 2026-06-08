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
package org.apache.tinkerpop.gremlin.process.traversal.util;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.util.LifetimeHelper;

import java.util.Date;

/**
 * Utility class for applying Allen temporal relationship filters to graph traversals.
 * This helper decomposes Allen interval operators into primitive has() predicates
 * for optimal execution performance.
 *
 * @author Alex Spitalas
 * @since 4.0.0-temporal
 */
public final class AllenStep {

    private AllenStep() {
        // Utility class, no instantiation
    }

    /**
     * Allen Temporal Relations enumeration.
     */
    public enum AllenRelation {
        BEFORE,
        AFTER,
        MEETS,
        MET_BY,
        OVERLAPS,
        OVERLAPPED_BY,
        STARTS,
        STARTED_BY,
        FINISHES,
        FINISHED_BY,
        DURING,
        CONTAINS,
        EQUALS
    }

    /**
     * Applies an Allen temporal filter to the given traversal.
     *
     * @param traversal the traversal to filter
     * @param relation the Allen temporal relation to apply
     * @param referenceElement the reference element to compare against
     * @param <S> the start type of the traversal
     * @param <E> the end type of the traversal
     * @return the traversal with temporal filter applied
     */
    public static <S, E> GraphTraversal<S, E> applyTemporalFilter(
            final GraphTraversal<S, E> traversal,
            final AllenRelation relation,
            final Element referenceElement) {

        final Date refStartTime = LifetimeHelper.getStartDateProperty(referenceElement);

        // Early exit if reference has no temporal properties
        if (refStartTime == null) {
            return traversal.limit(0);
        }

        final Date refEndTime = LifetimeHelper.getEndDateProperty(referenceElement);

        switch (relation) {
            case BEFORE:
                return applyBefore(traversal, refStartTime);

            case AFTER:
                return applyAfter(traversal, refEndTime);

            case MEETS:
                return applyMeets(traversal, refStartTime);

            case MET_BY:
                return applyMetBy(traversal, refEndTime);

            case OVERLAPS:
                return applyOverlaps(traversal, refStartTime, refEndTime);

            case OVERLAPPED_BY:
                return applyOverlappedBy(traversal, refStartTime, refEndTime);

            case STARTS:
                return applyStarts(traversal, refStartTime, refEndTime);

            case STARTED_BY:
                return applyStartedBy(traversal, refStartTime, refEndTime);

            case FINISHES:
                return applyFinishes(traversal, refStartTime, refEndTime);

            case FINISHED_BY:
                return applyFinishedBy(traversal, refStartTime, refEndTime);

            case DURING:
                return applyDuring(traversal, refStartTime, refEndTime);

            case CONTAINS:
                return applyContains(traversal, refStartTime, refEndTime);

            case EQUALS:
                return applyEquals(traversal, refStartTime, refEndTime);

            default:
                return traversal;
        }
    }

    // ========== Allen Relation Implementations ==========

    private static <S, E> GraphTraversal<S, E> applyBefore(
            final GraphTraversal<S, E> traversal,
            final Object refStartTime) {
        // X.endTime < Y.startTime
        return traversal.has("endTime", P.lt(refStartTime));
    }

    private static <S, E> GraphTraversal<S, E> applyAfter(
            final GraphTraversal<S, E> traversal,
            final Object refEndTime) {
        // X.startTime > Y.endTime
        if (refEndTime == null) {
            return traversal.limit(0);
        }
        return traversal.has("startTime", P.gt(refEndTime));
    }

    private static <S, E> GraphTraversal<S, E> applyMeets(
            final GraphTraversal<S, E> traversal,
            final Object refStartTime) {
        // X.endTime == Y.startTime
        return traversal.has("endTime", P.eq(refStartTime));
    }

    private static <S, E> GraphTraversal<S, E> applyMetBy(
            final GraphTraversal<S, E> traversal,
            final Object refEndTime) {
        // X.startTime == Y.endTime
        if (refEndTime == null) {
            return traversal.limit(0);
        }
        return traversal.has("startTime", P.eq(refEndTime));
    }

    private static <S, E> GraphTraversal<S, E> applyOverlaps(
            final GraphTraversal<S, E> traversal,
            final Object refStartTime,
            final Object refEndTime) {
        // X.startTime < Y.startTime AND X.endTime > Y.startTime AND X.endTime < Y.endTime
        GraphTraversal<S, E> result = traversal
                .has("startTime", P.lt(refStartTime))
                .has("endTime", P.gt(refStartTime));

        if (refEndTime != null) {
            result = result.has("endTime", P.lt(refEndTime));
        }

        return result;
    }

    private static <S, E> GraphTraversal<S, E> applyOverlappedBy(
            final GraphTraversal<S, E> traversal,
            final Object refStartTime,
            final Object refEndTime) {
        // X.startTime > Y.startTime AND X.startTime < Y.endTime AND X.endTime > Y.endTime
        GraphTraversal<S, E> result = traversal.has("startTime", P.gt(refStartTime));

        if (refEndTime != null) {
            result = result.has("startTime", P.lt(refEndTime))
                          .has("endTime", P.gt(refEndTime));
        }

        return result;
    }

    private static <S, E> GraphTraversal<S, E> applyStarts(
            final GraphTraversal<S, E> traversal,
            final Object refStartTime,
            final Object refEndTime) {
        // X.startTime == Y.startTime AND X.endTime < Y.endTime
        GraphTraversal<S, E> result = traversal.has("startTime", P.eq(refStartTime));

        if (refEndTime != null) {
            result = result.has("endTime", P.lt(refEndTime));
        }

        return result;
    }

    private static <S, E> GraphTraversal<S, E> applyStartedBy(
            final GraphTraversal<S, E> traversal,
            final Object refStartTime,
            final Object refEndTime) {
        // X.startTime == Y.startTime AND X.endTime > Y.endTime
        GraphTraversal<S, E> result = traversal.has("startTime", P.eq(refStartTime));

        if (refEndTime != null) {
            result = result.has("endTime", P.gt(refEndTime));
        }

        return result;
    }

    private static <S, E> GraphTraversal<S, E> applyFinishes(
            final GraphTraversal<S, E> traversal,
            final Object refStartTime,
            final Object refEndTime) {
        // X.startTime > Y.startTime AND X.endTime == Y.endTime
        if (refEndTime == null) {
            return traversal.limit(0);
        }

        return traversal.has("startTime", P.gt(refStartTime))
                       .has("endTime", P.eq(refEndTime));
    }

    private static <S, E> GraphTraversal<S, E> applyFinishedBy(
            final GraphTraversal<S, E> traversal,
            final Object refStartTime,
            final Object refEndTime) {
        // X.startTime < Y.startTime AND X.endTime == Y.endTime
        if (refEndTime == null) {
            return traversal.limit(0);
        }

        return traversal.has("startTime", P.lt(refStartTime))
                       .has("endTime", P.eq(refEndTime));
    }

    private static <S, E> GraphTraversal<S, E> applyDuring(
            final GraphTraversal<S, E> traversal,
            final Object refStartTime,
            final Object refEndTime) {
        // X.startTime > Y.startTime AND X.endTime < Y.endTime
        GraphTraversal<S, E> result = traversal.has("startTime", P.gt(refStartTime));

        if (refEndTime != null) {
            result = result.has("endTime", P.lt(refEndTime));
        }

        return result;
    }

    private static <S, E> GraphTraversal<S, E> applyContains(
            final GraphTraversal<S, E> traversal,
            final Object refStartTime,
            final Object refEndTime) {
        // X.startTime < Y.startTime AND X.endTime > Y.endTime
        GraphTraversal<S, E> result = traversal.has("startTime", P.lt(refStartTime));

        if (refEndTime != null) {
            result = result.has("endTime", P.gt(refEndTime));
        }

        return result;
    }

    private static <S, E> GraphTraversal<S, E> applyEquals(
            final GraphTraversal<S, E> traversal,
            final Object refStartTime,
            final Object refEndTime) {
        // X.startTime == Y.startTime AND X.endTime == Y.endTime
        GraphTraversal<S, E> result = traversal.has("startTime", P.eq(refStartTime));

        if (refEndTime != null) {
            result = result.has("endTime", P.eq(refEndTime));
        }

        return result;
    }

    // ========== Utility Methods ==========

    /**
     * Safely extracts a temporal property value from an element.
     *
     * @param element the element to extract from
     * @param propertyKey the property key
     * @return the property value or null if not present
     */
}
