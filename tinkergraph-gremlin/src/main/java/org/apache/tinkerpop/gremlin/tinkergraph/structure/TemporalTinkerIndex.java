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
import org.apache.tinkerpop.gremlin.structure.Vertex;
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
 * indexed value is treated as temporal when it is a {@link Lifetime} or when the indexed key is
 * {@link Lifetime#TEMPORAL_INTERVALS}.
 * <p/>
 * When {@link Lifetime#TEMPORAL_INTERVALS} is indexed, open-world temporal semantics are enforced across all graph
 * elements so that indexed and unindexed traversals (both {@code HasContainer} with {@code TemporalP} and
 * {@code AllenFilterStep}) behave identically:
 * <ul>
 *     <li><b>Static (untimed) elements</b> have an implicit {@link Lifetime#UNBOUNDED} ({@code [-infinity, +infinity)})
 *     lifetime ("alive forever") and are tracked in {@link #unboundedElements} in O(1) space per element without
 *     polluting {@link IntervalTree} subtree bounds.</li>
 *     <li><b>Bounded and semi-bounded elements</b> (configured via {@code .lifetime(...)}, {@code temporalIntervals},
 *     or {@code startTime}/{@code endTime} properties) have every interval of their {@link Lifetime} registered in the
 *     {@link IntervalTree}.</li>
 * </ul>
 * <p/>
 * Candidates are retrieved from the {@link IntervalTree} using Gérard Ligozat's (AAAI-91) endpoint inequations
 * {@code E(\pi)} and combined with {@link #unboundedElements} whenever {@link Lifetime#UNBOUNDED} satisfies the
 * queried {@link Temporal} relation. Callers still apply the full predicate to the returned candidates, which
 * {@code TinkerGraphStep} does by re-testing all of its {@code HasContainer}s and {@code AllenFilterStep}s.
 */
public class TemporalTinkerIndex<T extends Element> extends TinkerIndex<T> {

    protected Map<String, IntervalTree<T>> temporalIndex = new ConcurrentHashMap<>();
    protected Map<String, Map<T, Lifetime>> indexedLifetimes = new ConcurrentHashMap<>();
    protected Map<String, Set<T>> unboundedElements = new ConcurrentHashMap<>();

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

    private static boolean isTemporalPropertyKey(final String key) {
        return Lifetime.TEMPORAL_INTERVALS.equals(key)
                || Lifetime.START_TIME.equals(key)
                || Lifetime.END_TIME.equals(key);
    }

    /**
     * Synchronizes the element's effective open-world {@link Lifetime} (including {@link Lifetime#UNBOUNDED} for
     * static elements and {@code startTime}/{@code endTime}-derived lifetimes) with the
     * {@link Lifetime#TEMPORAL_INTERVALS} index if that key is currently indexed.
     */
    public void syncElementLifetime(final T element) {
        if (element == null || !this.indexClass.isAssignableFrom(element.getClass())) {
            return;
        }
        if (this.indexedKeys.contains(Lifetime.TEMPORAL_INTERVALS)) {
            if (element instanceof TinkerElement && ((TinkerElement) element).removed) {
                updateTree(Lifetime.TEMPORAL_INTERVALS, null, element);
            } else {
                updateTree(Lifetime.TEMPORAL_INTERVALS, Lifetime.getLifetimeFromProperties(element), element);
            }
        }
    }

    @Override
    public void createKeyIndex(final String key) {
        super.createKeyIndex(key);
        if (Lifetime.TEMPORAL_INTERVALS.equals(key)) {
            temporalIndex.computeIfAbsent(key, k -> new IntervalTree<>());
            unboundedElements.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
            (Vertex.class.isAssignableFrom(this.indexClass) ?
                    ((TinkerGraph) this.graph).vertices.values().stream() :
                    ((TinkerGraph) this.graph).edges.values().stream())
                    .forEach(e -> syncElementLifetime((T) e));
        }
    }

    @Override
    public void autoUpdate(final String key, final Object newValue, final Object oldValue, final T element) {
        super.autoUpdate(key, newValue, oldValue, element);
        if (isTemporalPropertyKey(key) && this.indexedKeys.contains(Lifetime.TEMPORAL_INTERVALS)) {
            syncElementLifetime(element);
        }
    }

    @Override
    protected void put(final String key, final Object value, final T element) {
        super.put(key, value, element);

        if (Lifetime.TEMPORAL_INTERVALS.equals(key)) {
            syncElementLifetime(element);
        } else {
            final Lifetime lifetime = toLifetime(key, value);
            if (lifetime != null) {
                updateTree(key, lifetime, element);
            }
        }
    }

    @Override
    public void remove(final String key, final Object value, final T element) {
        super.remove(key, value, element);
        if (isTemporalPropertyKey(key) && this.indexedKeys.contains(Lifetime.TEMPORAL_INTERVALS)) {
            syncElementLifetime(element);
        } else if (toLifetime(key, value) != null || indexedLifetimes.containsKey(key)) {
            updateTree(key, null, element);
        }
    }

    @Override
    public void removeElement(final T element) {
        super.removeElement(element);

        final Set<String> keys = new HashSet<>(temporalIndex.keySet());
        keys.addAll(indexedLifetimes.keySet());
        keys.addAll(unboundedElements.keySet());
        for (final String key : keys) {
            updateTree(key, null, element);
        }
    }

    @Override
    public void dropKeyIndex(final String key) {
        super.dropKeyIndex(key);
        temporalIndex.remove(key);
        indexedLifetimes.remove(key);
        unboundedElements.remove(key);
    }

    /**
     * Gets the candidate elements for a predicate. For a {@link Temporal} predicate the candidates are retrieved
     * from the {@link IntervalTree} using Ligozat's endpoint inequations (Section 2.2 & 3.2 of AAAI-91) and combined
     * with static ({@link Lifetime#UNBOUNDED}) elements when applicable; any other predicate is answered by the
     * regular equality index.
     */
    public List<T> getTemporal(final String key, final BiPredicate predicate, final Object value) {
        if (!(predicate instanceof Temporal))
            return super.get(key, value);

        final IntervalTree<T> tree = temporalIndex.get(key);
        final Set<T> unbounded = unboundedElements.get(key);
        if (tree == null && (unbounded == null || unbounded.isEmpty())) return Collections.emptyList();

        final Lifetime queryLifetime = Temporal.extractLifetime(value);
        if (queryLifetime == null || queryLifetime.isEmpty()) return Collections.emptyList();

        final Temporal temporalOp = (Temporal) predicate;
        final int count = queryLifetime.getIntervalCount();
        final long maxQueryStart = queryLifetime.getStartMillis(count - 1);
        final long minQueryEnd = queryLifetime.getEndMillis(0);

        final Set<T> candidates = new HashSet<>();
        if (tree != null) {
            switch (temporalOp) {
                case precedes:
                    candidates.addAll(tree.searchEndingBefore(maxQueryStart));
                    break;
                case meets:
                    for (int i = 0; i < count; i++) {
                        candidates.addAll(tree.searchByEndTime(queryLifetime.getStartMillis(i)));
                    }
                    break;
                case succeeds:
                    candidates.addAll(tree.searchStartingAfter(minQueryEnd));
                    break;
                case metBy:
                    for (int i = 0; i < count; i++) {
                        candidates.addAll(tree.searchByStartTime(queryLifetime.getEndMillis(i)));
                    }
                    break;
                default:
                    for (int i = 0; i < count; i++) {
                        candidates.addAll(tree.searchOverlaps(queryLifetime.getStartMillis(i), queryLifetime.getEndMillis(i)));
                    }
                    break;
            }
        }

        if (unbounded != null && !unbounded.isEmpty() && canMatchUnbounded(temporalOp, queryLifetime)) {
            candidates.addAll(unbounded);
        }
        return new ArrayList<>(candidates);
    }

    /**
     * Evaluates in O(1) whether a static element with {@link Lifetime#UNBOUNDED} ({@code [-infinity, +infinity)})
     * can satisfy {@code temporalOp} against {@code queryLifetime} under either {@link Temporal.Quantifier#ALL}
     * or {@link Temporal.Quantifier#ANY}.
     */
    private static boolean canMatchUnbounded(final Temporal temporalOp, final Lifetime queryLifetime) {
        return temporalOp.test(Lifetime.UNBOUNDED, queryLifetime, Temporal.Quantifier.ALL)
                || temporalOp.test(Lifetime.UNBOUNDED, queryLifetime, Temporal.Quantifier.ANY);
    }

    private void updateTree(final String key, final Lifetime newLifetime, final T element) {
        final Map<T, Lifetime> elemMap = indexedLifetimes.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        final Lifetime oldLifetime = elemMap.get(element);
        if (Objects.equals(oldLifetime, newLifetime)) {
            return;
        }
        final IntervalTree<T> tree = temporalIndex.computeIfAbsent(key, k -> new IntervalTree<>());
        final Set<T> unboundedSet = unboundedElements.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        if (oldLifetime != null) {
            elemMap.remove(element);
            if (oldLifetime.isUnbounded()) {
                unboundedSet.remove(element);
            } else {
                final int oldCount = oldLifetime.getIntervalCount();
                for (int i = 0; i < oldCount; i++) {
                    tree.remove(oldLifetime.getStartMillis(i), oldLifetime.getEndMillis(i), element);
                }
            }
        }
        if (newLifetime != null && !newLifetime.isEmpty()) {
            elemMap.put(element, newLifetime);
            if (newLifetime.isUnbounded()) {
                unboundedSet.add(element);
            } else {
                final int newCount = newLifetime.getIntervalCount();
                for (int i = 0; i < newCount; i++) {
                    tree.put(newLifetime.getStartMillis(i), newLifetime.getEndMillis(i), element);
                }
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
