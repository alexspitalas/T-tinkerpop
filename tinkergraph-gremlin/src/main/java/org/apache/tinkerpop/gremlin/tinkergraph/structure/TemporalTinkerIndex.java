package org.apache.tinkerpop.gremlin.tinkergraph.structure;

import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.process.traversal.Temporal;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

public class TemporalTinkerIndex<T extends Element> extends TinkerIndex<T> {

    protected Map<String, IntervalTree<T>> temporalIndex = new ConcurrentHashMap<>();

    public TemporalTinkerIndex(final TinkerGraph graph, final Class<T> indexClass) {
        super(graph, indexClass);
    }

    @Override
    protected void put(final String key, final Object value, final T element) {
        super.put(key, value, element);
        
        if (value instanceof Lifetime) {
            IntervalTree<T> tree = temporalIndex.computeIfAbsent(key, k -> new IntervalTree<>());
            for (Lifetime.Interval interval : ((Lifetime) value).getIntervals()) {
                tree.put(interval.getStart().getTime(), interval.getEnd().getTime(), element);
            }
        }
    }

    @Override
    public void remove(final String key, final Object value, final T element) {
        super.remove(key, value, element);
        
        if (value instanceof Lifetime) {
            IntervalTree<T> tree = temporalIndex.get(key);
            if (tree != null) {
                for (Lifetime.Interval interval : ((Lifetime) value).getIntervals()) {
                    tree.remove(interval.getStart().getTime(), interval.getEnd().getTime(), element);
                }
            }
        }
    }

    @Override
    public void removeElement(final T element) {
        super.removeElement(element);
        
        for (String key : this.getIndexedKeys()) {
            Property<Object> prop = element.property(key);
            if (prop.isPresent() && prop.value() instanceof Lifetime) {
                IntervalTree<T> tree = temporalIndex.get(key);
                if (tree != null) {
                    for (Lifetime.Interval interval : ((Lifetime) prop.value()).getIntervals()) {
                        tree.remove(interval.getStart().getTime(), interval.getEnd().getTime(), element);
                    }
                }
            }
        }
    }

    public List<T> getTemporal(final String key, final BiPredicate predicate, final Object value) {
        if (temporalIndex.containsKey(key) && predicate instanceof Temporal) {
            IntervalTree<T> tree = temporalIndex.get(key);
            Lifetime queryLifetime = extractLifetime(value);
            if (queryLifetime == null || queryLifetime.isEmpty()) return Collections.emptyList();
            
            // Search Overlaps retrieves a superset of candidates in O(log |E|)
            Set<T> candidates = new java.util.HashSet<>();
            for (Lifetime.Interval queryInterval : queryLifetime.getIntervals()) {
                candidates.addAll(tree.searchOverlaps(queryInterval.getStart().getTime(), queryInterval.getEnd().getTime()));
            }
            return new ArrayList<>(candidates);
        }
        return super.get(key, value);
    }
    
    private Lifetime extractLifetime(Object val) {
        if (val instanceof Lifetime) {
            return (Lifetime) val;
        } else if (val instanceof Element) {
            return Lifetime.fromProperties((Element) val);
        } else if (val instanceof Date) {
            return Lifetime.from(val, val);
        } else if (val instanceof Number) {
            Date d = new Date(((Number)val).longValue());
            return Lifetime.from(d, d);
        }
        return null;
    }
}
