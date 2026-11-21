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
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/**
 * Transactional property and temporal interval-tree index for {@link TinkerTransactionGraph}.
 * <p>
 * Committed temporal lifetimes are indexed in thread-safe {@link TemporalIndexCore} stores keyed by
 * {@link TinkerElementContainer}. Uncommitted per-transaction temporal writes, updates, and tombstones live in a
 * thread-local delta map ({@code txTemporal}) that is overlaid at query time and atomically merged into
 * {@code committedTemporal} during {@link #commit(Set)} (or discarded during {@link #rollback()}).
 *
 * @author Valentyn Kahamlyk
 */
final class TinkerTransactionalIndex<T extends TinkerElement> extends AbstractTinkerIndex<T> implements TemporalIndex<T> {

    protected Map<String, Map<Object, Set<TinkerElementContainer<T>>>> index = new ConcurrentHashMap<>();
    protected ThreadLocal<Map<String, Map<Object, Set<T>>>> txIndex =
            ThreadLocal.withInitial(() -> new ConcurrentHashMap<>());

    private final Map<String, TemporalIndexCore<TinkerElementContainer<T>>> committedTemporal = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String, Map<Object, TxTemporalEntry<T>>>> txTemporal = new ThreadLocal<>();
    private final ConcurrentHashMap<String, AtomicLong> keyGenerations = new ConcurrentHashMap<>();

    private static final class TxTemporalEntry<T extends TinkerElement> {
        final T element;
        final List<Lifetime> lifetimes; // empty list acts as a tombstone
        final long generation;

        TxTemporalEntry(final T element, final Collection<Lifetime> lifetimes, final long generation) {
            this.element = element;
            this.lifetimes = normalizeEntryLifetimes(lifetimes);
            this.generation = generation;
        }

        private static List<Lifetime> normalizeEntryLifetimes(final Collection<Lifetime> lifetimes) {
            if (lifetimes == null || lifetimes.isEmpty()) {
                return Collections.emptyList();
            }
            List<Lifetime> result = null;
            for (final Lifetime lt : lifetimes) {
                if (lt != null && !lt.isEmpty()) {
                    if (result == null) {
                        result = new ArrayList<>(lifetimes.size());
                    }
                    result.add(lt);
                }
            }
            return result == null ? Collections.emptyList() : result;
        }
    }

    public TinkerTransactionalIndex(final TinkerTransactionGraph graph, final Class<T> indexClass) {
        super(graph, indexClass);
    }

    private long currentGeneration(final String key) {
        return keyGenerations.computeIfAbsent(key, k -> new AtomicLong(0L)).get();
    }

    private void putTxElement(final String key, final Object value, final T element) {
        Map<String, Map<Object, Set<T>>> index = txIndex.get();
        if (index == null) {
            txIndex.set(new HashMap<>());
            index = txIndex.get();
        }

        Map<Object, Set<T>> keyMap = index.get(key);
        if (null == keyMap) {
            index.putIfAbsent(key, new ConcurrentHashMap<>());
            keyMap = index.get(key);
        }
        final Object indexableValue = indexable(value);
        Set<T> objects = keyMap.get(indexableValue);
        if (null == objects) {
            keyMap.putIfAbsent(indexableValue, ConcurrentHashMap.newKeySet());
            objects = keyMap.get(indexableValue);
        }
        objects.add(element);
    }

    private void putTxTemporal(final String key, final T element, final Lifetime lifetime) {
        putTxTemporalAll(key, element,
                (lifetime == null || lifetime.isEmpty()) ? Collections.emptyList() : Collections.singletonList(lifetime));
    }

    private void putTxTemporalAll(final String key, final T element, final Collection<Lifetime> lifetimes) {
        Map<String, Map<Object, TxTemporalEntry<T>>> perKey = txTemporal.get();
        if (perKey == null) {
            perKey = new HashMap<>();
            txTemporal.set(perKey);
        }
        final long gen = currentGeneration(key);
        perKey.computeIfAbsent(key, k -> new HashMap<>())
                .put(element.id(), new TxTemporalEntry<>(element, lifetimes, gen));
    }

    private boolean hasTxTemporalKey(final String key) {
        final Map<String, Map<Object, TxTemporalEntry<T>>> perKey = txTemporal.get();
        return perKey != null && perKey.containsKey(key);
    }

    private void syncTxCustomKeyLifetimes(final String key, final T element, final Object oldValue) {
        final List<Lifetime> lifetimes = element.removed
                ? Collections.emptyList()
                : TemporalIndex.collectCustomLifetimes(element, key);
        if (!lifetimes.isEmpty()) {
            putTxTemporalAll(key, element, lifetimes);
        } else if (TemporalIndex.toLifetime(key, oldValue) != null
                || committedTemporal.containsKey(key)
                || hasTxTemporalKey(key)) {
            putTxTemporalAll(key, element, Collections.emptyList());
        }
    }

    private List<T> getNotModifiedElements(final String key, final Object value) {
        final Map<Object, Set<TinkerElementContainer<T>>> keyMap = index.get(key);
        if (null == keyMap)
            return new ArrayList<>();

        Set<TinkerElementContainer<T>> set = keyMap.get(indexable(value));
        if (null == set)
            return new ArrayList<>();

        return set.stream().
                filter(e -> !e.isChanged() && e.get() != null).
                map(e -> e.get()).collect(Collectors.toList());
    }

    private Set<T> getModifiedElements(final String key, final Object value) {
        final Map<String, Map<Object, Set<T>>> index = txIndex.get();
        if (null == index)
            return null;

        final Map<Object, Set<T>> keyMap = index.get(key);
        if (null == keyMap)
            return null;

        return keyMap.get(indexable(value));
    }

    @Override
    public List<T> get(final String key, final Object value) {
        final List<T> notModifiedElements = getNotModifiedElements(key, value);
        final Set<T> modifiedElements = getModifiedElements(key, value);
        if (modifiedElements != null)
            modifiedElements.forEach(e -> {
                if (!notModifiedElements.contains(e)) notModifiedElements.add(e);
            });

        return notModifiedElements;
    }

    @Override
    public List<T> getTemporal(final String key, final BiPredicate predicate, final Object value) {
        graph.tx().readWrite();
        if (!(predicate instanceof Temporal)) {
            return get(key, value);
        }
        final Lifetime queryLifetime = Temporal.extractLifetime(value);
        if (queryLifetime == null || queryLifetime.isEmpty()) {
            return Collections.emptyList();
        }
        final Temporal temporalOp = (Temporal) predicate;
        final TemporalIndexCore<TinkerElementContainer<T>> store = committedTemporal.get(key);
        final Map<String, Map<Object, TxTemporalEntry<T>>> perKey = txTemporal.get();
        final Map<Object, TxTemporalEntry<T>> txMap = perKey != null ? perKey.get(key) : null;

        if (store == null && (txMap == null || txMap.isEmpty())) {
            return Collections.emptyList();
        }

        final long expectedGen = currentGeneration(key);
        final TinkerTransaction tx = (TinkerTransaction) graph.tx();
        final List<T> results = new ArrayList<>();

        if (store != null) {
            tx.commitVisibilityLock.readLock().lock();
            try {
                final Set<TinkerElementContainer<T>> committedMatches = store.query(temporalOp, queryLifetime);
                for (final TinkerElementContainer<T> container : committedMatches) {
                    if (container.isDeleted()) {
                        continue;
                    }
                    if (txMap != null) {
                        final TxTemporalEntry<T> txEntry = txMap.get(container.getElementId());
                        if (txEntry != null && txEntry.generation == expectedGen) {
                            continue;
                        }
                    }
                    final T elem = container.getWithClone(tx);
                    if (elem != null && !elem.removed) {
                        results.add(elem);
                    }
                }
            } finally {
                tx.commitVisibilityLock.readLock().unlock();
            }
        }

        if (txMap != null && !txMap.isEmpty()) {
            for (final TxTemporalEntry<T> entry : txMap.values()) {
                if (entry.generation != expectedGen || entry.lifetimes.isEmpty() || entry.element.removed) {
                    continue;
                }
                for (final Lifetime lt : entry.lifetimes) {
                    if (temporalOp.test(lt, queryLifetime, Temporal.Quantifier.ALL)
                            || temporalOp.test(lt, queryLifetime, Temporal.Quantifier.ANY)) {
                        results.add(entry.element);
                        break;
                    }
                }
            }
        }

        return results;
    }

    @Override
    public void syncElementLifetime(final T element) {
        if (element == null || !this.indexClass.isAssignableFrom(element.getClass())) {
            return;
        }
        if (this.indexedKeys.contains(Lifetime.TEMPORAL_INTERVALS)) {
            if (element.removed) {
                putTxTemporal(Lifetime.TEMPORAL_INTERVALS, element, null);
            } else {
                putTxTemporal(Lifetime.TEMPORAL_INTERVALS, element, TemporalIndex.resolveElementLifetimeForIndex(element));
            }
        }
    }

    @Override
    public long count(final String key, final Object value) {
        return get(key, value).size();
    }

    private void removeTxElement(final String key, final Object value, final T element) {
        final Map<String, Map<Object, Set<T>>> localIdx = txIndex.get();
        if (null != localIdx) {
            final Map<Object, Set<T>> keyMap = localIdx.get(key);
            if (null != keyMap) {
                final Set<T> objects = keyMap.get(indexable(value));
                if (null != objects) {
                    objects.remove(element);
                    if (objects.isEmpty())
                        keyMap.remove(indexable(value));
                }
            }
        }
    }

    @Override
    public void remove(final String key, final Object value, final T element) {
        removeTxElement(key, value, element);
        if (TemporalIndex.isTemporalPropertyKey(key) && this.indexedKeys.contains(Lifetime.TEMPORAL_INTERVALS)) {
            syncElementLifetime(element);
        }
        if (!Lifetime.TEMPORAL_INTERVALS.equals(key) && this.indexedKeys.contains(key)) {
            syncTxCustomKeyLifetimes(key, element, value);
        }
    }

    @Override
    public void removeElement(final T element) {
        if (indexClass.isAssignableFrom(element.getClass())) {
            element.properties().forEachRemaining(p -> {
                        if (p.isPresent() && indexedKeys.contains(p.key())) {
                            remove(p.key(), p.value(), element);
                        }
                    }
            );
            if (this.indexedKeys.contains(Lifetime.TEMPORAL_INTERVALS)) {
                putTxTemporal(Lifetime.TEMPORAL_INTERVALS, element, null);
            }
            for (final String key : committedTemporal.keySet()) {
                putTxTemporal(key, element, null);
            }
            final Map<String, Map<Object, TxTemporalEntry<T>>> localTemporal = txTemporal.get();
            if (localTemporal != null) {
                for (final String key : localTemporal.keySet()) {
                    putTxTemporal(key, element, null);
                }
            }
        }
    }

    private void put(final String key, final Object value, final TinkerElementContainer<T> container) {
        Map<Object, Set<TinkerElementContainer<T>>> keyMap = index.get(key);
        if (null == keyMap) {
            index.putIfAbsent(key, new ConcurrentHashMap<>());
            keyMap = index.get(key);
        }
        final Object indexableValue = indexable(value);
        Set<TinkerElementContainer<T>> objects = keyMap.get(indexableValue);
        if (null == objects) {
            keyMap.putIfAbsent(indexableValue, ConcurrentHashMap.newKeySet());
            objects = keyMap.get(indexableValue);
        }
        objects.add(container);
    }

    private void addContainer(final TinkerElementContainer<T> container) {
        final T element = container.get();
        if (element == null || !indexClass.isAssignableFrom(element.getClass()))
            return;

        if (element.properties().hasNext()) {
            element.properties().forEachRemaining(p -> {
                if (p.isPresent() && indexedKeys.contains(p.key())) {
                    put(p.key(), p.value(), container);
                }
            });
        }

        for (final String key : this.indexedKeys) {
            if (Lifetime.TEMPORAL_INTERVALS.equals(key)) {
                committedTemporal.computeIfAbsent(Lifetime.TEMPORAL_INTERVALS, k -> new TemporalIndexCore<>())
                        .update(container, Lifetime.getLifetimeFromProperties(element));
            } else {
                final List<Lifetime> lifetimes = TemporalIndex.collectCustomLifetimes(element, key);
                if (!lifetimes.isEmpty()) {
                    committedTemporal.computeIfAbsent(key, k -> new TemporalIndexCore<>())
                            .updateAll(container, lifetimes);
                } else {
                    final TemporalIndexCore<TinkerElementContainer<T>> store = committedTemporal.get(key);
                    if (store != null) {
                        store.remove(container);
                    }
                }
            }
        }
    }

    @Override
    public void autoUpdate(final String key, final Object newValue, final Object oldValue, final T element) {
        if (indexedKeys.contains(key)) {
            removeTxElement(key, oldValue, element);
            putTxElement(key, newValue, element);
        }
        if (TemporalIndex.isTemporalPropertyKey(key) && this.indexedKeys.contains(Lifetime.TEMPORAL_INTERVALS)) {
            syncElementLifetime(element);
        }
        if (!Lifetime.TEMPORAL_INTERVALS.equals(key) && indexedKeys.contains(key)) {
            syncTxCustomKeyLifetimes(key, element, oldValue);
        }
    }

    @Override
    public void createKeyIndex(final String key) {
        if (null == key)
            throw Graph.Exceptions.argumentCanNotBeNull("key");
        if (key.isEmpty())
            throw new IllegalArgumentException("The key for the index cannot be an empty string");

        if (indexedKeys.contains(key))
            return;

        if (Lifetime.TEMPORAL_INTERVALS.equals(key)) {
            committedTemporal.computeIfAbsent(key, k -> new TemporalIndexCore<>());
        }

        final Map elements =
                Vertex.class.isAssignableFrom(indexClass) ?
                        ((TinkerTransactionGraph) graph).getVertices() :
                        ((TinkerTransactionGraph) graph).getEdges();

        for (final Object obj : elements.values()) {
            final TinkerElementContainer<T> container = (TinkerElementContainer<T>) obj;
            final T committed = container.getUnmodified();
            if (committed != null && indexClass.isAssignableFrom(committed.getClass())) {
                if (committed.properties(key).hasNext()) {
                    committed.properties(key).forEachRemaining(p -> {
                        if (p.isPresent()) {
                            put(key, p.value(), container);
                        }
                    });
                }
                if (Lifetime.TEMPORAL_INTERVALS.equals(key)) {
                    committedTemporal.computeIfAbsent(key, k -> new TemporalIndexCore<>())
                            .update(container, Lifetime.getLifetimeFromProperties(committed));
                } else {
                    final List<Lifetime> committedLifetimes = TemporalIndex.collectCustomLifetimes(committed, key);
                    if (!committedLifetimes.isEmpty()) {
                        committedTemporal.computeIfAbsent(key, k -> new TemporalIndexCore<>())
                                .updateAll(container, committedLifetimes);
                    }
                }
            }
            if (container.isChanged()) {
                final T draft = container.getModified();
                if (container.isDeleted()) {
                    final T ref = draft != null ? draft : committed;
                    if (ref != null && (Lifetime.TEMPORAL_INTERVALS.equals(key) || committedTemporal.containsKey(key))) {
                        putTxTemporal(key, ref, null);
                    }
                } else if (draft != null && indexClass.isAssignableFrom(draft.getClass())) {
                    if (draft.properties(key).hasNext()) {
                        final Iterator<? extends Property<?>> it = draft.properties(key);
                        while (it.hasNext()) {
                            final Property<?> p = it.next();
                            if (p.isPresent()) {
                                putTxElement(key, p.value(), draft);
                            }
                        }
                    }
                    if (Lifetime.TEMPORAL_INTERVALS.equals(key)) {
                        putTxTemporal(key, draft, TemporalIndex.resolveElementLifetimeForIndex(draft));
                    } else {
                        final List<Lifetime> draftLifetimes = TemporalIndex.collectCustomLifetimes(draft, key);
                        if (!draftLifetimes.isEmpty()) {
                            putTxTemporalAll(key, draft, draftLifetimes);
                        } else if (committedTemporal.containsKey(key)) {
                            putTxTemporalAll(key, draft, Collections.emptyList());
                        }
                    }
                }
            }
        }

        indexedKeys.add(key);
    }

    @Override
    public void dropKeyIndex(final String key) {
        indexedKeys.remove(key);
        if (key != null) {
            keyGenerations.computeIfAbsent(key, k -> new AtomicLong(0L)).incrementAndGet();
        }

        if (index.containsKey(key))
            index.remove(key).clear();

        final Map<String, Map<Object, Set<T>>> localIdx = txIndex.get();
        if (localIdx != null && localIdx.containsKey(key))
            localIdx.remove(key).clear();

        final TemporalIndexCore<TinkerElementContainer<T>> removedStore = committedTemporal.remove(key);
        if (removedStore != null) {
            removedStore.clear();
        }
        final Map<String, Map<Object, TxTemporalEntry<T>>> localTemporal = txTemporal.get();
        if (localTemporal != null) {
            localTemporal.remove(key);
        }
    }

    private void removeContainer(final TinkerElementContainer<T> container, final boolean removeTemporal) {
        final T element = container.getUnmodified();
        if (element != null && indexClass.isAssignableFrom(element.getClass()) && element.properties().hasNext()) {
            element.properties().forEachRemaining(p -> {
                final Map<Object, Set<TinkerElementContainer<T>>> keyMap = index.get(p.key());
                final Object indexableValue = indexable(p.value());
                if (null != keyMap) {
                    final Set<TinkerElementContainer<T>> objects = keyMap.get(indexableValue);
                    if (null != objects) {
                        objects.remove(container);
                        if (objects.isEmpty())
                            keyMap.remove(indexableValue);
                    }
                }
            });
        }
        if (removeTemporal) {
            for (final TemporalIndexCore<TinkerElementContainer<T>> store : committedTemporal.values()) {
                store.remove(container);
            }
        } else if (element != null && !committedTemporal.isEmpty()) {
            // For non-deleted elements, only remove custom Lifetime keys that were removed in the updated draft;
            // TEMPORAL_INTERVALS and retained Lifetime keys are diff-updated in O(1) by TemporalIndexCore.updateAll().
            final T draft = container.get();
            for (final Map.Entry<String, TemporalIndexCore<TinkerElementContainer<T>>> entry : committedTemporal.entrySet()) {
                final String key = entry.getKey();
                if (Lifetime.TEMPORAL_INTERVALS.equals(key)) {
                    continue;
                }
                if (draft == null || TemporalIndex.collectCustomLifetimes(draft, key).isEmpty()) {
                    entry.getValue().remove(container);
                }
            }
        }
    }

    public void commit(final Set<TinkerElementContainer<T>> updatedElements) {
        for (final TinkerElementContainer<T> element : updatedElements) {
            final boolean deleted = element.isDeleted();
            removeContainer(element, deleted);
            if (!deleted)
                addContainer(element);
        }

        txIndex.remove();
        txTemporal.remove();
    }

    public void rollback() {
        txIndex.remove();
        txTemporal.remove();
    }
}
