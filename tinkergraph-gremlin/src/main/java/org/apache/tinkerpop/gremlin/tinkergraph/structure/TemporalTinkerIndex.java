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
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * A {@link TinkerIndex} that additionally maintains an {@link IntervalTree} for keys holding a temporal lifetime. An
 * indexed value is treated as temporal when it is a {@link Lifetime} or when it is the persisted
 * {@link Lifetime#TEMPORAL_INTERVALS} string of an element. Every interval of the lifetime is registered in the
 * tree, so that {@link Temporal} predicates can be answered without scanning all elements.
 * <p/>
 * Candidates are retrieved by interval overlap, which is a superset of the matches of every {@link Temporal}
 * predicate except {@link Temporal#precedes}, {@link Temporal#succeeds}, {@link Temporal#meets}, and
 * {@link Temporal#metBy} (see {@link #supports(BiPredicate)}).
 * Callers must therefore still apply the actual predicate to the returned candidates, which
 * {@code TinkerGraphStep} does by re-testing all of its {@code HasContainer}s.
 */
public class TemporalTinkerIndex<T extends Element> extends TinkerIndex<T> {

    protected Map<String, IntervalTree<T>> temporalIndex = new ConcurrentHashMap<>();
    protected Map<String, Map<T, Lifetime>> indexedLifetimes = new ConcurrentHashMap<>();

    public TemporalTinkerIndex(final TinkerGraph graph, final Class<T> indexClass) {
        super(graph, indexClass);
    }

    /**
     * Determines if the index can narrow down the candidates of the given predicate.
     * Using Gérard Ligozat's (AAAI-91) endpoint inequations {@code E(\pi)} on the {@link IntervalTree},
     * all 13 Allen {@link Temporal} operators and {@link Temporal#intersects} are supported by the index.
     * Only {@link Temporal#disjoint} and {@link Temporal#generalized} are excluded from single-tree candidate narrowing.
     */
    public static boolean supports(final BiPredicate<?, ?> predicate) {
        return predicate instanceof Temporal
                && predicate != Temporal.disjoint
                && predicate != Temporal.generalized;
    }

    @Override
    protected void put(final String key, final Object value, final T element) {
        super.put(key, value, element);

        final Lifetime lifetime = toLifetime(key, value);
        if (lifetime != null) {
            updateTree(key, lifetime, element);
        }
    }

    @Override
    public void remove(final String key, final Object value, final T element) {
        super.remove(key, value, element);
        if (toLifetime(key, value) != null || indexedLifetimes.containsKey(key)) {
            updateTree(key, null, element);
        }
    }

    @Override
    public void removeElement(final T element) {
        super.removeElement(element);

        for (String key : temporalIndex.keySet()) {
            updateTree(key, null, element);
        }
    }

    @Override
    public void dropKeyIndex(final String key) {
        super.dropKeyIndex(key);
        temporalIndex.remove(key);
        indexedLifetimes.remove(key);
    }

    /**
     * Gets the candidate elements for a predicate. For a {@link Temporal} predicate the candidates are retrieved
     * from the {@link IntervalTree} using Ligozat's endpoint inequations (Section 2.2 & 3.2 of AAAI-91);
     * any other predicate is answered by the regular equality index.
     */
    public List<T> getTemporal(final String key, final BiPredicate predicate, final Object value) {
        if (!(predicate instanceof Temporal))
            return super.get(key, value);

        final IntervalTree<T> tree = temporalIndex.get(key);
        if (tree == null) return Collections.emptyList();

        final Lifetime queryLifetime = Temporal.extractLifetime(value);
        if (queryLifetime == null || queryLifetime.isEmpty()) return Collections.emptyList();

        final Temporal temporalOp = (Temporal) predicate;
        final int count = queryLifetime.getIntervalCount();
        final long maxQueryStart = queryLifetime.getStartMillis(count - 1);
        final long minQueryEnd = queryLifetime.getEndMillis(0);

        switch (temporalOp) {
            case precedes:
                return new ArrayList<>(tree.searchEndingBefore(maxQueryStart));
            case meets: {
                if (count == 1) {
                    return new ArrayList<>(tree.searchByEndTime(queryLifetime.getStartMillis(0)));
                }
                final Set<T> candidates = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    candidates.addAll(tree.searchByEndTime(queryLifetime.getStartMillis(i)));
                }
                return new ArrayList<>(candidates);
            }
            case succeeds:
                return new ArrayList<>(tree.searchStartingAfter(minQueryEnd));
            case metBy: {
                if (count == 1) {
                    return new ArrayList<>(tree.searchByStartTime(queryLifetime.getEndMillis(0)));
                }
                final Set<T> candidates = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    candidates.addAll(tree.searchByStartTime(queryLifetime.getEndMillis(i)));
                }
                return new ArrayList<>(candidates);
            }
            default: {
                if (count == 1) {
                    return new ArrayList<>(tree.searchOverlaps(queryLifetime.getStartMillis(0), queryLifetime.getEndMillis(0)));
                }
                final Set<T> candidates = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    candidates.addAll(tree.searchOverlaps(queryLifetime.getStartMillis(i), queryLifetime.getEndMillis(i)));
                }
                return new ArrayList<>(candidates);
            }
        }
    }

    private void updateTree(final String key, final Lifetime newLifetime, final T element) {
        final Map<T, Lifetime> elemMap = indexedLifetimes.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        final Lifetime oldLifetime = elemMap.get(element);
        if (Objects.equals(oldLifetime, newLifetime)) {
            return;
        }
        final IntervalTree<T> tree = temporalIndex.computeIfAbsent(key, k -> new IntervalTree<>());
        if (oldLifetime != null) {
            elemMap.remove(element);
            final int oldCount = oldLifetime.getIntervalCount();
            for (int i = 0; i < oldCount; i++) {
                tree.remove(oldLifetime.getStartMillis(i), oldLifetime.getEndMillis(i), element);
            }
        }
        if (newLifetime != null && !newLifetime.isEmpty()) {
            elemMap.put(element, newLifetime);
            final int newCount = newLifetime.getIntervalCount();
            for (int i = 0; i < newCount; i++) {
                tree.put(newLifetime.getStartMillis(i), newLifetime.getEndMillis(i), element);
            }
        }
    }

    private static Lifetime toLifetime(final String key, final Object value) {
        if (value instanceof Lifetime) return (Lifetime) value;

        if (value instanceof String && Lifetime.TEMPORAL_INTERVALS.equals(key)) {
            try {
                return Lifetime.fromTemporalIntervals((String) value);
            } catch (final IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}
