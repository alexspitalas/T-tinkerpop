package org.apache.tinkerpop.gremlin.tinkergraph.structure;

import org.apache.tinkerpop.gremlin.process.traversal.Temporal;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * predicate except {@link Temporal#precedes} and {@link Temporal#succeeds} (see {@link #supports(BiPredicate)}).
 * Callers must therefore still apply the actual predicate to the returned candidates, which
 * {@code TinkerGraphStep} does by re-testing all of its {@code HasContainer}s.
 */
public class TemporalTinkerIndex<T extends Element> extends TinkerIndex<T> {

    protected Map<String, IntervalTree<T>> temporalIndex = new ConcurrentHashMap<>();

    public TemporalTinkerIndex(final TinkerGraph graph, final Class<T> indexClass) {
        super(graph, indexClass);
    }

    /**
     * Determines if the index can narrow down the candidates of the given predicate. All {@link Temporal}
     * predicates require the two lifetimes to share at least one instant, except {@link Temporal#precedes} and
     * {@link Temporal#succeeds} where they are disjoint by definition and an overlap search is useless.
     */
    public static boolean supports(final BiPredicate<?, ?> predicate) {
        return predicate instanceof Temporal && predicate != Temporal.precedes && predicate != Temporal.succeeds;
    }

    @Override
    protected void put(final String key, final Object value, final T element) {
        super.put(key, value, element);

        final Lifetime lifetime = toLifetime(key, value);
        if (lifetime != null) {
            final IntervalTree<T> tree = temporalIndex.computeIfAbsent(key, k -> new IntervalTree<>());
            for (Lifetime.Interval interval : lifetime.getIntervals()) {
                tree.put(interval.getStart().getTime(), interval.getEnd().getTime(), element);
            }
        }
    }

    @Override
    public void remove(final String key, final Object value, final T element) {
        super.remove(key, value, element);
        removeFromTree(key, toLifetime(key, value), element);
    }

    @Override
    public void removeElement(final T element) {
        super.removeElement(element);

        for (String key : temporalIndex.keySet()) {
            final Property<Object> prop = element.property(key);
            if (prop.isPresent()) {
                removeFromTree(key, toLifetime(key, prop.value()), element);
            }
        }
    }

    @Override
    public void dropKeyIndex(final String key) {
        super.dropKeyIndex(key);
        temporalIndex.remove(key);
    }

    /**
     * Gets the candidate elements for a predicate. For a {@link Temporal} predicate the candidates are the elements
     * with a lifetime interval that overlaps an interval of the query value; any other predicate is answered by the
     * regular equality index.
     */
    public List<T> getTemporal(final String key, final BiPredicate predicate, final Object value) {
        if (!(predicate instanceof Temporal))
            return super.get(key, value);

        final IntervalTree<T> tree = temporalIndex.get(key);
        if (tree == null) return Collections.emptyList();

        final Lifetime queryLifetime = Temporal.extractLifetime(value);
        if (queryLifetime == null || queryLifetime.isEmpty()) return Collections.emptyList();

        final Set<T> candidates = new HashSet<>();
        for (Lifetime.Interval queryInterval : queryLifetime.getIntervals()) {
            candidates.addAll(tree.searchOverlaps(queryInterval.getStart().getTime(), queryInterval.getEnd().getTime()));
        }
        return new ArrayList<>(candidates);
    }

    private void removeFromTree(final String key, final Lifetime lifetime, final T element) {
        if (lifetime == null) return;

        final IntervalTree<T> tree = temporalIndex.get(key);
        if (tree != null) {
            for (Lifetime.Interval interval : lifetime.getIntervals()) {
                tree.remove(interval.getStart().getTime(), interval.getEnd().getTime(), element);
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
