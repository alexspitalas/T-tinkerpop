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

import org.apache.tinkerpop.gremlin.process.traversal.Temporal;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Common contract for TinkerGraph indices (both non-transactional {@link TemporalTinkerIndex} and transactional
 * {@link TinkerTransactionalIndex}) that maintain an {@link IntervalTree} for temporal lifetime predicates.
 */
public interface TemporalIndex<T extends Element> {

    /**
     * Retrieves candidate elements matching a {@link Temporal} predicate on {@code key}, combining
     * {@link IntervalTree} endpoint-inequation lookups with open-world {@link Lifetime#UNBOUNDED} elements.
     */
    List<T> getTemporal(String key, BiPredicate predicate, Object value);

    /**
     * Synchronizes the element's effective open-world {@link Lifetime} with the
     * {@link Lifetime#TEMPORAL_INTERVALS} index if that key is currently indexed.
     */
    void syncElementLifetime(T element);

    /**
     * Determines if an interval-tree temporal index can narrow down candidates for the given predicate.
     * Supports all 13 Allen {@link Temporal} operators and {@link Temporal#intersects}.
     */
    static boolean supports(final BiPredicate<?, ?> predicate) {
        return predicate instanceof Temporal
                && predicate != Temporal.disjoint
                && predicate != Temporal.generalized;
    }

    static boolean isTemporalPropertyKey(final String key) {
        return Lifetime.TEMPORAL_INTERVALS.equals(key)
                || Lifetime.START_TIME.equals(key)
                || Lifetime.END_TIME.equals(key);
    }

    static Lifetime toLifetime(final String key, final Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Temporal.extractLifetime(value);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * Collects all non-empty temporal {@link Lifetime} values currently attached to {@code element} under custom
     * property {@code key} (supporting {@code single}, {@code list}, and {@code set} cardinalities as well as mixed
     * temporal/non-temporal values).
     */
    static List<Lifetime> collectCustomLifetimes(final Element element, final String key) {
        final Iterator<? extends Property<?>> it = element.properties(key);
        if (!it.hasNext()) {
            return Collections.emptyList();
        }
        List<Lifetime> lifetimes = null;
        while (it.hasNext()) {
            final Property<?> p = it.next();
            if (p.isPresent()) {
                final Lifetime lt = toLifetime(key, p.value());
                if (lt != null && !lt.isEmpty()) {
                    if (lifetimes == null) {
                        lifetimes = new ArrayList<>(2);
                    }
                    lifetimes.add(lt);
                }
            }
        }
        return lifetimes == null ? Collections.emptyList() : lifetimes;
    }

    /**
     * Resolves an element's effective {@link Lifetime} for the {@link Lifetime#TEMPORAL_INTERVALS} index.
     * When {@link Lifetime#TEMPORAL_INTERVALS} is absent and sequential {@link Lifetime#START_TIME}/{@link Lifetime#END_TIME}
     * updates transiently produce {@code startTime > endTime}, returns {@code null} instead of throwing so indexed
     * graphs behave identically to unindexed graphs.
     */
    static Lifetime resolveElementLifetimeForIndex(final Element element) {
        if (element.properties(Lifetime.TEMPORAL_INTERVALS).hasNext()) {
            return Lifetime.getLifetimeFromProperties(element);
        }
        try {
            return Lifetime.getLifetimeFromProperties(element);
        } catch (final IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Evaluates in O(1) whether a static element with {@link Lifetime#UNBOUNDED} ({@code [-infinity, +infinity)})
     * can satisfy {@code temporalOp} against {@code queryLifetime} under either {@link Temporal.Quantifier#ALL}
     * or {@link Temporal.Quantifier#ANY}.
     */
    static boolean canMatchUnbounded(final Temporal temporalOp, final Lifetime queryLifetime) {
        return temporalOp.test(Lifetime.UNBOUNDED, queryLifetime, Temporal.Quantifier.ALL)
                || temporalOp.test(Lifetime.UNBOUNDED, queryLifetime, Temporal.Quantifier.ANY);
    }
}
