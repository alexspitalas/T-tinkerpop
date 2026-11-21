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
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe per-key temporal index store combining an {@link IntervalTree} for bounded/semi-bounded lifetimes
 * with an O(1) {@code unboundedElements} set for open-world {@link Lifetime#UNBOUNDED} entries. Supports both
 * single-valued keys (such as {@link Lifetime#TEMPORAL_INTERVALS}) and multi-valued ({@code list}/{@code set})
 * custom temporal properties.
 * <p>
 * Guarded by a {@link ReentrantReadWriteLock} so concurrent queries observe a consistent snapshot across the
 * interval tree, lifetime tracking map, and unbounded set.
 *
 * @param <K> indexed entry type ({@code T} for non-transactional graphs, {@code TinkerElementContainer<T>} for
 *            transactional graphs)
 */
final class TemporalIndexCore<K> {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private IntervalTree<K> tree = new IntervalTree<>();
    private final Map<K, Set<Lifetime>> indexedLifetimes = new HashMap<>();
    private final Set<K> unboundedElements = new HashSet<>();

    /**
     * Atomically replaces {@code entry}'s indexed lifetime with {@code newLifetime} (or removes it when
     * {@code newLifetime} is {@code null} or empty).
     */
    void update(final K entry, final Lifetime newLifetime) {
        final Lifetime normalized = (newLifetime != null && !newLifetime.isEmpty()) ? newLifetime : null;
        updateAll(entry, normalized == null ? Collections.emptySet() : Collections.singleton(normalized));
    }

    /**
     * Atomically replaces all indexed lifetimes for {@code entry} with {@code newLifetimes} (or removes {@code entry}
     * when {@code newLifetimes} has no non-empty lifetimes).
     */
    void updateAll(final K entry, final Collection<Lifetime> newLifetimes) {
        final Set<Lifetime> normalized = normalizeLifetimes(newLifetimes);
        lock.writeLock().lock();
        try {
            final Set<Lifetime> oldLifetimes = indexedLifetimes.get(entry);
            if (normalized.isEmpty()) {
                if (oldLifetimes == null) {
                    return;
                }
                indexedLifetimes.remove(entry);
                removeLifetimesFromStructures(entry, oldLifetimes);
                return;
            }
            if (normalized.equals(oldLifetimes)) {
                return;
            }
            if (oldLifetimes != null) {
                removeLifetimesFromStructures(entry, oldLifetimes);
            }
            indexedLifetimes.put(entry, normalized);
            addLifetimesToStructures(entry, normalized);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static Set<Lifetime> normalizeLifetimes(final Collection<Lifetime> lifetimes) {
        if (lifetimes == null || lifetimes.isEmpty()) {
            return Collections.emptySet();
        }
        if (lifetimes.size() == 1) {
            final Lifetime single = lifetimes.iterator().next();
            return (single == null || single.isEmpty()) ? Collections.emptySet() : Collections.singleton(single);
        }
        Set<Lifetime> result = null;
        for (final Lifetime lt : lifetimes) {
            if (lt != null && !lt.isEmpty()) {
                if (result == null) {
                    result = new HashSet<>();
                }
                result.add(lt);
            }
        }
        return (result == null || result.isEmpty()) ? Collections.emptySet() : result;
    }

    private void removeLifetimesFromStructures(final K entry, final Set<Lifetime> lifetimes) {
        for (final Lifetime lt : lifetimes) {
            if (lt.isUnbounded()) {
                unboundedElements.remove(entry);
            } else {
                final int count = lt.getIntervalCount();
                for (int i = 0; i < count; i++) {
                    tree.remove(lt.getStartMillis(i), lt.getEndMillis(i), entry);
                }
            }
        }
    }

    private void addLifetimesToStructures(final K entry, final Set<Lifetime> lifetimes) {
        for (final Lifetime lt : lifetimes) {
            if (lt.isUnbounded()) {
                unboundedElements.add(entry);
            } else {
                final int count = lt.getIntervalCount();
                for (int i = 0; i < count; i++) {
                    tree.put(lt.getStartMillis(i), lt.getEndMillis(i), entry);
                }
            }
        }
    }

    void remove(final K entry) {
        updateAll(entry, Collections.emptySet());
    }

    /**
     * Queries candidate entries using Ligozat's (AAAI-91) endpoint inequations on the {@link IntervalTree}
     * combined with {@code unboundedElements} when {@link Lifetime#UNBOUNDED} can satisfy {@code temporalOp}.
     */
    Set<K> query(final Temporal temporalOp, final Lifetime queryLifetime) {
        if (queryLifetime == null || queryLifetime.isEmpty()) {
            return Collections.emptySet();
        }
        final int count = queryLifetime.getIntervalCount();
        final long maxQueryStart = queryLifetime.getStartMillis(count - 1);
        final long minQueryEnd = queryLifetime.getEndMillis(0);

        lock.readLock().lock();
        try {
            if (indexedLifetimes.isEmpty()) {
                return Collections.emptySet();
            }
            final Set<K> candidates = new HashSet<>();
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
            if (!unboundedElements.isEmpty() && TemporalIndex.canMatchUnbounded(temporalOp, queryLifetime)) {
                candidates.addAll(unboundedElements);
            }
            return candidates;
        } finally {
            lock.readLock().unlock();
        }
    }

    void clear() {
        lock.writeLock().lock();
        try {
            tree = new IntervalTree<>();
            indexedLifetimes.clear();
            unboundedElements.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
