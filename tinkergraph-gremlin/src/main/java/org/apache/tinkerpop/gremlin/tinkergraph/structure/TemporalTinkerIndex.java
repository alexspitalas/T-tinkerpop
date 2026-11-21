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
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * A {@link TinkerIndex} that additionally maintains a thread-safe {@link IntervalTree} via {@link TemporalIndexCore}
 * for keys holding a temporal lifetime. An indexed value is treated as temporal when it is extractable as a
 * {@link Lifetime} or when the indexed key is {@link Lifetime#TEMPORAL_INTERVALS}.
 */
public class TemporalTinkerIndex<T extends Element> extends TinkerIndex<T> implements TemporalIndex<T> {

    private final Map<String, TemporalIndexCore<T>> temporalStores = new ConcurrentHashMap<>();

    public TemporalTinkerIndex(final TinkerGraph graph, final Class<T> indexClass) {
        super(graph, indexClass);
    }

    /**
     * Determines if the index can narrow down the candidates of the given predicate.
     */
    public static boolean supports(final BiPredicate<?, ?> predicate) {
        return TemporalIndex.supports(predicate);
    }

    @Override
    public void syncElementLifetime(final T element) {
        if (element == null || !this.indexClass.isAssignableFrom(element.getClass())) {
            return;
        }
        if (this.indexedKeys.contains(Lifetime.TEMPORAL_INTERVALS)) {
            syncElementLifetimeInternal(element);
        }
    }

    private void syncElementLifetimeInternal(final T element) {
        if (element instanceof TinkerElement && ((TinkerElement) element).removed) {
            updateStore(Lifetime.TEMPORAL_INTERVALS, null, element);
        } else {
            updateStore(Lifetime.TEMPORAL_INTERVALS, TemporalIndex.resolveElementLifetimeForIndex(element), element);
        }
    }

    private void syncCustomKeyLifetimes(final String key, final T element) {
        if (element == null || !this.indexClass.isAssignableFrom(element.getClass())) {
            return;
        }
        if (element instanceof TinkerElement && ((TinkerElement) element).removed) {
            updateStoreAll(key, Collections.emptyList(), element);
            return;
        }
        updateStoreAll(key, TemporalIndex.collectCustomLifetimes(element, key), element);
    }

    @Override
    public void createKeyIndex(final String key) {
        if (null == key)
            throw Graph.Exceptions.argumentCanNotBeNull("key");
        if (key.isEmpty())
            throw new IllegalArgumentException("The key for the index cannot be an empty string");

        if (this.indexedKeys.contains(key))
            return;

        if (Lifetime.TEMPORAL_INTERVALS.equals(key)) {
            temporalStores.computeIfAbsent(key, k -> new TemporalIndexCore<>());
        }

        final Collection<?> elements = Vertex.class.isAssignableFrom(this.indexClass) ?
                ((TinkerGraph) this.graph).vertices.values() :
                ((TinkerGraph) this.graph).edges.values();

        for (final Object obj : elements) {
            final T element = (T) obj;
            if (element == null || !this.indexClass.isAssignableFrom(element.getClass())) {
                continue;
            }
            final Iterator<? extends Property<?>> it = element.properties(key);
            while (it.hasNext()) {
                final Property<?> p = it.next();
                if (p.isPresent()) {
                    super.put(key, p.value(), element);
                }
            }
            if (Lifetime.TEMPORAL_INTERVALS.equals(key)) {
                syncElementLifetimeInternal(element);
            } else {
                syncCustomKeyLifetimes(key, element);
            }
        }

        this.indexedKeys.add(key);
    }

    @Override
    public void autoUpdate(final String key, final Object newValue, final Object oldValue, final T element) {
        super.autoUpdate(key, newValue, oldValue, element);
        if (TemporalIndex.isTemporalPropertyKey(key) && this.indexedKeys.contains(Lifetime.TEMPORAL_INTERVALS)) {
            syncElementLifetime(element);
        }
    }

    @Override
    protected void put(final String key, final Object value, final T element) {
        super.put(key, value, element);

        if (Lifetime.TEMPORAL_INTERVALS.equals(key)) {
            syncElementLifetime(element);
        } else {
            syncCustomKeyLifetimes(key, element);
        }
    }

    @Override
    public void remove(final String key, final Object value, final T element) {
        super.remove(key, value, element);
        if (TemporalIndex.isTemporalPropertyKey(key) && this.indexedKeys.contains(Lifetime.TEMPORAL_INTERVALS)) {
            syncElementLifetime(element);
        }
        if (!Lifetime.TEMPORAL_INTERVALS.equals(key)
                && (TemporalIndex.toLifetime(key, value) != null || temporalStores.containsKey(key))) {
            syncCustomKeyLifetimes(key, element);
        }
    }

    @Override
    public void removeElement(final T element) {
        super.removeElement(element);
        for (final TemporalIndexCore<T> store : temporalStores.values()) {
            store.remove(element);
        }
    }

    @Override
    public void dropKeyIndex(final String key) {
        this.indexedKeys.remove(key);
        super.dropKeyIndex(key);
        final TemporalIndexCore<T> removed = temporalStores.remove(key);
        if (removed != null) {
            removed.clear();
        }
    }

    @Override
    public List<T> getTemporal(final String key, final BiPredicate predicate, final Object value) {
        if (!(predicate instanceof Temporal)) {
            return super.get(key, value);
        }
        final TemporalIndexCore<T> store = temporalStores.get(key);
        if (store == null) {
            return Collections.emptyList();
        }
        final Lifetime queryLifetime = Temporal.extractLifetime(value);
        if (queryLifetime == null || queryLifetime.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(store.query((Temporal) predicate, queryLifetime));
    }

    private void updateStore(final String key, final Lifetime newLifetime, final T element) {
        if (newLifetime == null || newLifetime.isEmpty()) {
            final TemporalIndexCore<T> store = temporalStores.get(key);
            if (store != null) {
                store.remove(element);
            }
            return;
        }
        temporalStores.computeIfAbsent(key, k -> new TemporalIndexCore<>()).update(element, newLifetime);
    }

    private void updateStoreAll(final String key, final List<Lifetime> newLifetimes, final T element) {
        if (newLifetimes == null || newLifetimes.isEmpty()) {
            final TemporalIndexCore<T> store = temporalStores.get(key);
            if (store != null) {
                store.remove(element);
            }
            return;
        }
        temporalStores.computeIfAbsent(key, k -> new TemporalIndexCore<>()).updateAll(element, newLifetimes);
    }
}
