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

import org.apache.tinkerpop.gremlin.process.computer.util.ComputerGraph;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.star.StarGraph;
import org.apache.tinkerpop.gremlin.structure.util.wrapped.WrappedEdge;
import org.apache.tinkerpop.gremlin.structure.util.wrapped.WrappedVertex;
import org.apache.tinkerpop.gremlin.structure.util.wrapped.WrappedVertexProperty;
import org.apache.tinkerpop.gremlin.util.DatetimeHelper;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Lifetime implements Serializable {
    public enum DropMode {
        SOFT,
        HARD
    }

    private static final long serialVersionUID = 2L;

    public static final String START_TIME = "startTime";
    public static final String END_TIME = "endTime";
    public static final String TEMPORAL_INTERVALS = "temporalIntervals";
    public static final String MAX_END_TIME = "+292278994-08-17T07:12:55.807Z";
    public static final String MIN_START_TIME = "-292275055-05-16T16:47:04.192Z";
    public static final Date MIN_DATE = toDate(MIN_START_TIME, "Start time");
    public static final Date MAX_DATE = toDate(MAX_END_TIME, "End time");
    public static final long MIN_MILLIS = MIN_DATE.getTime();
    public static final long MAX_MILLIS = MAX_DATE.getTime();
    private static final long[] EMPTY_RAW = new long[0];
    public static final Lifetime UNBOUNDED = new Lifetime(new long[]{MIN_MILLIS, MAX_MILLIS}, true);
    public static final Lifetime EMPTY = new Lifetime(EMPTY_RAW, true);

    public static Lifetime empty() {
        return EMPTY;
    }

    public static class Interval implements Serializable, Comparable<Interval> {
        private static final long serialVersionUID = 1L;
        private final long startMillis;
        private final long endMillis;

        public Interval(final Date start, final Date end) {
            this(Objects.requireNonNull(start, "start cannot be null").getTime(),
                 Objects.requireNonNull(end, "end cannot be null").getTime());
        }

        public Interval(final long startMillis, final long endMillis) {
            if (endMillis < startMillis)
                throw new IllegalArgumentException("End time must not be before start time");
            this.startMillis = startMillis;
            this.endMillis = endMillis;
        }

        public Date getStart() { return new Date(startMillis); }
        public Date getEnd() { return new Date(endMillis); }
        public long getStartMillis() { return startMillis; }
        public long getEndMillis() { return endMillis; }

        public boolean contains(final Date instant) {
            if (instant == null) return false;
            return contains(instant.getTime());
        }

        public boolean contains(final long instantMillis) {
            return containsInstant(this.startMillis, this.endMillis, instantMillis);
        }

        public boolean contains(final Interval other) {
            if (other == null) return false;
            return containsRange(this.startMillis, this.endMillis, other.startMillis, other.endMillis);
        }

        public boolean intersects(final Interval other) {
            if (other == null) return false;
            return intersectsRange(this.startMillis, this.endMillis, other.startMillis, other.endMillis);
        }

        public Interval intersection(final Interval other) {
            if (!intersects(other)) return null;
            final long maxStart = Math.max(this.startMillis, other.startMillis);
            final long minEnd = Math.min(this.endMillis, other.endMillis);
            return new Interval(maxStart, minEnd);
        }

        @Override
        public int compareTo(final Interval other) {
            final int c = Long.compare(this.startMillis, other.startMillis);
            if (c != 0) return c;
            return Long.compare(this.endMillis, other.endMillis);
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final Interval interval = (Interval) o;
            return startMillis == interval.startMillis && endMillis == interval.endMillis;
        }

        @Override
        public int hashCode() {
            return 31 * Long.hashCode(startMillis) + Long.hashCode(endMillis);
        }
    }

    private final long[] rawIntervals;
    private transient List<Interval> intervals;

    private Lifetime(final long[] rawIntervals, final boolean normalized) {
        this.rawIntervals = normalized ? rawIntervals : normalizeRaw(rawIntervals);
    }

    private Lifetime(final List<Interval> intervals) {
        if (intervals == null || intervals.isEmpty()) {
            this.rawIntervals = EMPTY_RAW;
        } else {
            final int n = intervals.size();
            final long[] raw = new long[n << 1];
            for (int i = 0; i < n; i++) {
                final Interval iv = Objects.requireNonNull(intervals.get(i), "Interval cannot be null");
                raw[i << 1] = iv.startMillis;
                raw[(i << 1) + 1] = iv.endMillis;
            }
            this.rawIntervals = normalizeRaw(raw);
        }
    }

    private Lifetime(final Date startDate, final Date endDate) {
        this(Objects.requireNonNull(startDate, "start cannot be null").getTime(),
             Objects.requireNonNull(endDate, "end cannot be null").getTime());
    }

    private Lifetime(final long startMillis, final long endMillis) {
        if (endMillis < startMillis)
            throw new IllegalArgumentException("End time must not be before start time");
        this.rawIntervals = new long[]{startMillis, endMillis};
    }

    private static boolean containsInstant(final long startMillis, final long endMillis, final long instantMillis) {
        if (startMillis == endMillis) return startMillis == instantMillis;
        return instantMillis >= startMillis && instantMillis < endMillis;
    }

    private static boolean containsRange(final long startA, final long endA, final long startB, final long endB) {
        if (startB == endB) {
            return containsInstant(startA, endA, startB);
        }
        if (startA == endA) {
            return false;
        }
        return startA <= startB && endA >= endB;
    }

    private static boolean intersectsRange(final long startA, final long endA, final long startB, final long endB) {
        if (startA == endA) {
            if (startB == endB) return startA == startB;
            return startB <= startA && startA < endB;
        }
        if (startB == endB) {
            return startA <= startB && startB < endA;
        }
        return startA < endB && startB < endA;
    }

    private static long[] normalizeRaw(final long[] input) {
        if (input == null || input.length == 0) return EMPTY_RAW;
        final int n = input.length >>> 1;
        if (n == 1) {
            if (input[1] < input[0])
                throw new IllegalArgumentException("End time must not be before start time");
            return input;
        }

        // Fast check: see if input is already sorted and normalized
        boolean alreadyNormalized = true;
        for (int i = 0; i < n; i++) {
            final long s = input[i << 1];
            final long e = input[(i << 1) + 1];
            if (e < s)
                throw new IllegalArgumentException("End time must not be before start time");
            if (i > 0) {
                final long prevS = input[(i - 1) << 1];
                final long prevE = input[((i - 1) << 1) + 1];
                if (s < prevS || (s == prevS && e < prevE) || canMergeRange(prevS, prevE, s, e)) {
                    alreadyNormalized = false;
                    break;
                }
            }
        }
        if (alreadyNormalized) {
            return input;
        }

        // Sort intervals by (start, end)
        final long[] sorted = input.clone();
        sortIntervalPairs(sorted, n);

        final long[] merged = new long[n << 1];
        int mergedCount = 0;
        long curStart = sorted[0];
        long curEnd = sorted[1];

        for (int i = 1; i < n; i++) {
            final long nextStart = sorted[i << 1];
            final long nextEnd = sorted[(i << 1) + 1];
            if (canMergeRange(curStart, curEnd, nextStart, nextEnd)) {
                curEnd = Math.max(curEnd, nextEnd);
                if (mergedCount > 0) {
                    final int prevIdx = (mergedCount - 1) << 1;
                    final long prevStart = merged[prevIdx];
                    final long prevEnd = merged[prevIdx + 1];
                    if (canMergeRange(prevStart, prevEnd, curStart, curEnd)) {
                        mergedCount--;
                        curStart = prevStart;
                        curEnd = Math.max(prevEnd, curEnd);
                    }
                }
            } else {
                final int idx = mergedCount << 1;
                merged[idx] = curStart;
                merged[idx + 1] = curEnd;
                mergedCount++;
                curStart = nextStart;
                curEnd = nextEnd;
            }
        }
        final int idx = mergedCount << 1;
        merged[idx] = curStart;
        merged[idx + 1] = curEnd;
        mergedCount++;

        return mergedCount == n ? merged : Arrays.copyOf(merged, mergedCount << 1);
    }

    private static void sortIntervalPairs(final long[] pairs, final int n) {
        // Insertion sort for small arrays, Dual-Pivot-like quicksort/shellsort for larger arrays
        for (int gap = n >>> 1; gap > 0; gap >>>= 1) {
            for (int i = gap; i < n; i++) {
                final long tempStart = pairs[i << 1];
                final long tempEnd = pairs[(i << 1) + 1];
                int j = i;
                while (j >= gap) {
                    final int prevIdx = (j - gap) << 1;
                    final long prevStart = pairs[prevIdx];
                    final long prevEnd = pairs[prevIdx + 1];
                    if (prevStart > tempStart || (prevStart == tempStart && prevEnd > tempEnd)) {
                        pairs[j << 1] = prevStart;
                        pairs[(j << 1) + 1] = prevEnd;
                        j -= gap;
                    } else {
                        break;
                    }
                }
                pairs[j << 1] = tempStart;
                pairs[(j << 1) + 1] = tempEnd;
            }
        }
    }

    private static boolean canMergeRange(final long leftStart, final long leftEnd, final long rightStart, final long rightEnd) {
        if (leftEnd < rightStart)
            return false;
        final boolean leftIsHalfOpen = leftStart != leftEnd;
        final boolean rightIsPoint = rightStart == rightEnd;
        return !(leftIsHalfOpen && rightIsPoint && leftEnd == rightStart);
    }

    public static Lifetime from(final Object startDate, final Object endDate) {
        if (startDate == null && endDate == null)
            return UNBOUNDED;
        final long startMs = toStartMillis(startDate);
        final long endMs = toEndMillis(endDate);
        if (endMs < startMs)
            throw new IllegalArgumentException("End time must not be before start time");
        if (startMs <= MIN_MILLIS && endMs >= MAX_MILLIS)
            return UNBOUNDED;
        return new Lifetime(startMs, endMs);
    }

    public static Lifetime from(final List<Interval> intervals) {
        if (intervals.isEmpty())
            return EMPTY;
        final Lifetime lt = new Lifetime(intervals);
        return lt.isEmpty() ? EMPTY : (lt.isUnbounded() ? UNBOUNDED : lt);
    }

    public static Lifetime fromRawIntervals(final long[] rawIntervals) {
        if (rawIntervals == null || rawIntervals.length == 0)
            return EMPTY;
        if ((rawIntervals.length & 1) != 0)
            throw new IllegalArgumentException("rawIntervals length must be even");
        final Lifetime lt = new Lifetime(rawIntervals.clone(), false);
        return lt.isEmpty() ? EMPTY : (lt.isUnbounded() ? UNBOUNDED : lt);
    }

    public static Lifetime fromProperties(final Element element) {
        if (element instanceof TemporalElement) {
            final Lifetime cached = ((TemporalElement) element).getLifetime();
            if (cached != null)
                return cached;
        }
        final Lifetime parsed = fromPropertiesRaw(element);
        if (element instanceof TemporalElement) {
            ((TemporalElement) element).setLifetime(parsed);
        }
        return parsed;
    }

    public static Lifetime fromPropertiesRaw(final Element element) {
        final Property<Object> tempIntervalsProp = propertyOrEmpty(element, TEMPORAL_INTERVALS);
        if (tempIntervalsProp.isPresent()) {
            final Object value = tempIntervalsProp.value();
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("Invalid temporalIntervals value (expected String): " + value);
            }
            return fromTemporalIntervalsString((String) value);
        }
        final Property<Object> startTimeProperty = propertyOrEmpty(element, START_TIME);
        final Property<Object> endTimeProperty = propertyOrEmpty(element, END_TIME);
        return from(startTimeProperty.orElse(null), endTimeProperty.orElse(null));
    }

    /**
     * Parses the persisted {@link #TEMPORAL_INTERVALS} property value (comma-separated {@code start:end} epoch
     * millisecond pairs) into a {@link Lifetime}.
     *
     * @throws IllegalArgumentException if the value is not a valid {@code temporalIntervals} string
     */
    public static Lifetime fromTemporalIntervals(final String value) {
        if (null == value)
            throw new IllegalArgumentException("temporalIntervals value cannot be null");
        return fromTemporalIntervalsString(value);
    }

    private static Lifetime fromTemporalIntervalsString(final String value) {
        final String[] components = value.split(",", -1);
        final int n = components.length;
        final long[] raw = new long[n << 1];
        for (int i = 0; i < n; i++) {
            final String component = components[i];
            final int colon = component.indexOf(':');
            if (colon <= 0 || colon != component.lastIndexOf(':') || colon == component.length() - 1) {
                throw invalidTemporalIntervals(value, component);
            }

            final String startValue = component.substring(0, colon);
            final String endValue = component.substring(colon + 1);
            if (!isSignedDecimalInteger(startValue) || !isSignedDecimalInteger(endValue)) {
                throw invalidTemporalIntervals(value, component);
            }

            final long start;
            final long end;
            try {
                start = Long.parseLong(startValue);
                end = Long.parseLong(endValue);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid temporalIntervals component '" + component + "' in value '" + value + "'", e);
            }
            if (end < start) {
                throw new IllegalArgumentException(
                        "Invalid temporalIntervals component '" + component + "' (end before start) in value '" + value + "'");
            }
            raw[i << 1] = start;
            raw[(i << 1) + 1] = end;
        }
        final Lifetime lt = new Lifetime(raw, false);
        return lt.isUnbounded() ? UNBOUNDED : lt;
    }

    private static boolean isSignedDecimalInteger(final String value) {
        if (value == null || value.isEmpty()) return false;
        int i = 0;
        final char first = value.charAt(0);
        if (first == '+' || first == '-') {
            i = 1;
            if (value.length() == 1) return false;
        }
        for (; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static IllegalArgumentException invalidTemporalIntervals(final String value, final String component) {
        return new IllegalArgumentException(
                "Invalid temporalIntervals component '" + component + "' in value '" + value + "'");
    }

    public String toTemporalIntervalsString() {
        final int count = rawIntervals.length >>> 1;
        if (count == 0) return "";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(rawIntervals[i << 1]).append(':').append(rawIntervals[(i << 1) + 1]);
            if (i < count - 1) sb.append(',');
        }
        return sb.toString();
    }

    public static boolean hasLifetimeProperties(final Element element) {
        final Property<Object> startProp = propertyOrEmpty(element, START_TIME);
        if (startProp.isPresent() || startProp.orElse(null) != null) return true;
        final Property<Object> endProp = propertyOrEmpty(element, END_TIME);
        if (endProp.isPresent() || endProp.orElse(null) != null) return true;
        final Property<Object> ivProp = propertyOrEmpty(element, TEMPORAL_INTERVALS);
        return ivProp.isPresent() || ivProp.orElse(null) != null;
    }

    public static Lifetime getLifetimeFromProperties(final Element element) {
        if (element instanceof TemporalElement &&
                !(element instanceof WrappedEdge || element instanceof WrappedVertex || element instanceof WrappedVertexProperty)) {
            final Lifetime cached = ((TemporalElement) element).getLifetime();
            if (cached != null)
                return cached;
        }
        return fromProperties(unwrapElement(element));
    }

    public static Element unwrapElement(final Element element) {
        Element current = element;
        while (true) {
            if (current instanceof WrappedVertex) {
                final Object base = ((WrappedVertex<?>) current).getBaseVertex();
                if (!(base instanceof Vertex) || base == current)
                    break;
                current = (Vertex) base;
            } else if (current instanceof WrappedEdge) {
                final Object base = ((WrappedEdge<?>) current).getBaseEdge();
                if (!(base instanceof Edge) || base == current)
                    break;
                current = (Edge) base;
            } else if (current instanceof WrappedVertexProperty) {
                final Object base = ((WrappedVertexProperty<?>) current).getBaseVertexProperty();
                if (!(base instanceof VertexProperty) || base == current)
                    break;
                current = (VertexProperty<?>) base;
            } else {
                break;
            }
        }
        return current;
    }

    public static Element unwrapTemporalElement(final Element element) {
        Element current = element;
        while (true) {
            if (current instanceof TemporalVertex) {
                final Vertex base = ((TemporalVertex) current).getBaseVertex();
                if (base == null || base == current)
                    break;
                current = base;
            } else if (current instanceof TemporalEdge) {
                final Edge base = ((TemporalEdge) current).getBaseEdge();
                if (base == null || base == current)
                    break;
                current = base;
            } else if (current instanceof TemporalVertexProperty) {
                final VertexProperty<?> base = ((TemporalVertexProperty<?>) current).getBaseVertexProperty();
                if (base == null || base == current)
                    break;
                current = base;
            } else {
                break;
            }
        }
        return current;
    }

    public Object[] toProperties() {
        if (rawIntervals.length == 0 || isUnbounded()) return new Object[0];
        return new Object[] {
                START_TIME, getStartDate(),
                END_TIME, getEndDate(),
                TEMPORAL_INTERVALS, toTemporalIntervalsString()
        };
    }

    public Map<String, Object> toPropertyMap() {
        final Map<String, Object> properties = new LinkedHashMap<>();
        if (rawIntervals.length != 0 && !isUnbounded()) {
            properties.put(START_TIME, getStartDate());
            properties.put(END_TIME, getEndDate());
            properties.put(TEMPORAL_INTERVALS, toTemporalIntervalsString());
        }
        return properties;
    }

    public void attachTo(final Element element) {
        if (rawIntervals.length == 0) {
            propertyOrEmpty(element, START_TIME).remove();
            propertyOrEmpty(element, END_TIME).remove();
            propertyOrEmpty(element, TEMPORAL_INTERVALS).remove();
            if (element instanceof TemporalElement) {
                ((TemporalElement) element).setLifetime(this);
            }
            return;
        }
        if (element instanceof Vertex) {
            final Vertex vertex = (Vertex) element;
            vertex.property(VertexProperty.Cardinality.single, START_TIME, getStartDate());
            vertex.property(VertexProperty.Cardinality.single, END_TIME, getEndDate());
            vertex.property(VertexProperty.Cardinality.single, TEMPORAL_INTERVALS, toTemporalIntervalsString());
        } else {
            element.property(START_TIME, getStartDate());
            element.property(END_TIME, getEndDate());
            element.property(TEMPORAL_INTERVALS, toTemporalIntervalsString());
        }
        if (element instanceof TemporalElement) {
            ((TemporalElement) element).setLifetime(this);
        }
    }

    public Lifetime addInterval(final Object start, final Object end) {
        return addInterval(toStartMillis(start), toEndMillis(end));
    }

    public Lifetime addInterval(final long s, final long e) {
        if (e < s)
            throw new IllegalArgumentException("End time must not be before start time");
        final int n = rawIntervals.length >>> 1;
        if (n == 0)
            return from(s, e);
        final long lastS = rawIntervals[(n - 1) << 1];
        final long lastE = rawIntervals[((n - 1) << 1) + 1];
        if (s > lastS && !canMergeRange(lastS, lastE, s, e)) {
            final long[] newRaw = Arrays.copyOf(rawIntervals, (n + 1) << 1);
            newRaw[n << 1] = s;
            newRaw[(n << 1) + 1] = e;
            return new Lifetime(newRaw, true);
        }
        if (s >= lastS && lastS != lastE && canMergeRange(lastS, lastE, s, e)) {
            if (e <= lastE)
                return this;
            final long[] newRaw = rawIntervals.clone();
            newRaw[((n - 1) << 1) + 1] = e;
            return (newRaw.length == 2 && newRaw[0] <= MIN_MILLIS && newRaw[1] >= MAX_MILLIS)
                    ? UNBOUNDED
                    : new Lifetime(newRaw, true);
        }
        final long[] newRaw = Arrays.copyOf(rawIntervals, (n + 1) << 1);
        newRaw[n << 1] = s;
        newRaw[(n << 1) + 1] = e;
        final Lifetime lt = new Lifetime(newRaw, false);
        return lt.isUnbounded() ? UNBOUNDED : lt;
    }

    public Lifetime dropInterval(final Object start, final Object end) {
        return dropInterval(toStartMillis(start), toEndMillis(end));
    }

    public Lifetime dropInterval(final long dropStart, final long dropEnd) {
        if (dropEnd < dropStart)
            throw new IllegalArgumentException("End time must not be before start time");
        final int n = rawIntervals.length >>> 1;
        final long[] temp = new long[(n + 1) << 1];
        int count = 0;
        for (int i = 0; i < n; i++) {
            final long curStart = rawIntervals[i << 1];
            final long curEnd = rawIntervals[(i << 1) + 1];
            if (intersectsRange(curStart, curEnd, dropStart, dropEnd)) {
                if (curStart < dropStart) {
                    final int idx = count << 1;
                    temp[idx] = curStart;
                    temp[idx + 1] = dropStart;
                    count++;
                }
                if (curEnd > dropEnd) {
                    final int idx = count << 1;
                    temp[idx] = dropEnd;
                    temp[idx + 1] = curEnd;
                    count++;
                }
            } else {
                final int idx = count << 1;
                temp[idx] = curStart;
                temp[idx + 1] = curEnd;
                count++;
            }
        }
        if (count == 0) return EMPTY;
        return new Lifetime(Arrays.copyOf(temp, count << 1), false);
    }

    public Lifetime intersection(final Lifetime window) {
        if (window == null || this.isEmpty() || window.isEmpty()) return EMPTY;
        if (this.isUnbounded()) return window;
        if (window.isUnbounded()) return this;

        final long[] a = this.rawIntervals;
        final long[] b = window.rawIntervals;
        final int na = a.length >>> 1;
        final int nb = b.length >>> 1;

        int i = 0, j = 0;
        final long[] temp = new long[(na + nb) << 1];
        int count = 0;

        while (i < na && j < nb) {
            final long s1 = a[i << 1];
            final long e1 = a[(i << 1) + 1];
            final long s2 = b[j << 1];
            final long e2 = b[(j << 1) + 1];

            if (intersectsRange(s1, e1, s2, e2)) {
                final long maxStart = Math.max(s1, s2);
                final long minEnd = Math.min(e1, e2);
                final int idx = count << 1;
                temp[idx] = maxStart;
                temp[idx + 1] = minEnd;
                count++;
            }

            if (e1 < e2) {
                i++;
            } else if (e2 < e1) {
                j++;
            } else if (s1 < s2 && s2 == e2) {
                i++;
            } else if (s2 < s1 && s1 == e1) {
                j++;
            } else {
                i++;
                j++;
            }
        }

        if (count == 0) return EMPTY;
        final long[] res = (count == temp.length >>> 1) ? temp : Arrays.copyOf(temp, count << 1);
        final Lifetime lt = new Lifetime(res, false);
        return lt.isEmpty() ? EMPTY : (lt.isUnbounded() ? UNBOUNDED : lt);
    }

    public boolean contains(final Date instant) {
        if (instant == null || rawIntervals.length == 0) return false;
        return contains(instant.getTime());
    }

    public boolean contains(final long instantMillis) {
        if (rawIntervals.length == 0) return false;
        final long start = rawIntervals[0];
        final long end = rawIntervals[rawIntervals.length - 1];
        if (instantMillis < start || instantMillis > end) return false;
        final int n = rawIntervals.length >>> 1;
        for (int i = 0; i < n; i++) {
            final long s = rawIntervals[i << 1];
            final long e = rawIntervals[(i << 1) + 1];
            if (containsInstant(s, e, instantMillis)) return true;
            if (s > instantMillis) break;
        }
        return false;
    }

    public boolean intersects(final long start, final long end) {
        if (isEmpty()) return false;
        if (isUnbounded()) return true;
        final int count = rawIntervals.length >>> 1;
        for (int i = 0; i < count; i++) {
            final long s = rawIntervals[i << 1];
            final long e = rawIntervals[(i << 1) + 1];
            if (intersectsRange(s, e, start, end)) return true;
        }
        return false;
    }

    public boolean contains(final long start, final long end) {
        if (isEmpty()) return false;
        if (isUnbounded()) return true;
        final int count = rawIntervals.length >>> 1;
        for (int i = 0; i < count; i++) {
            final long s = rawIntervals[i << 1];
            final long e = rawIntervals[(i << 1) + 1];
            if (containsRange(s, e, start, end)) return true;
        }
        return false;
    }

    public boolean intersects(final Lifetime window) {
        if (window == null) return false;
        final long[] r1 = this.rawIntervals;
        final long[] r2 = window.rawIntervals;
        if (r1.length == 0 || r2.length == 0) return false;
        if (this.isUnbounded() || window.isUnbounded()) return true;

        final int n1 = r1.length >>> 1;
        final int n2 = r2.length >>> 1;
        if (n1 == 1 && n2 == 1) {
            return intersectsRange(r1[0], r1[1], r2[0], r2[1]);
        }

        if (r1[r1.length - 1] < r2[0] || r2[r2.length - 1] < r1[0]) return false;

        int i = 0, j = 0;
        while (i < n1 && j < n2) {
            final long s1 = r1[i << 1];
            final long e1 = r1[(i << 1) + 1];
            final long s2 = r2[j << 1];
            final long e2 = r2[(j << 1) + 1];
            if (intersectsRange(s1, e1, s2, e2)) return true;
            if (e1 < e2) {
                i++;
            } else if (e2 < e1) {
                j++;
            } else {
                if (s1 < s2) i++; else j++;
            }
        }
        return false;
    }

    public boolean contains(final Lifetime other) {
        if (other == null) return false;
        if (other.isEmpty()) return true;
        if (this.isEmpty()) return false;
        if (this.isUnbounded()) return true;

        final long[] a = this.rawIntervals;
        final long[] b = other.rawIntervals;
        if (b[0] < a[0] || b[b.length - 1] > a[a.length - 1]) return false;

        final int na = a.length >>> 1;
        final int nb = b.length >>> 1;

        int i = 0;
        for (int j = 0; j < nb; j++) {
            final long sB = b[j << 1];
            final long eB = b[(j << 1) + 1];
            while (i < na && (a[(i << 1) + 1] < eB
                    || (sB == eB && a[(i << 1) + 1] == eB && a[i << 1] < eB))) {
                i++;
            }
            if (i >= na) return false;
            final long sA = a[i << 1];
            final long eA = a[(i << 1) + 1];
            if (!containsRange(sA, eA, sB, eB)) {
                return false;
            }
        }
        return true;
    }

    public Date getStartDate() {
        if (rawIntervals.length == 0) return toStartDate(null);
        return new Date(rawIntervals[0]);
    }

    public Date getEndDate() {
        if (rawIntervals.length == 0) return toEndDate(null);
        return new Date(rawIntervals[rawIntervals.length - 1]);
    }

    public long getStartMillis() {
        return rawIntervals.length == 0 ? MIN_MILLIS : rawIntervals[0];
    }

    public long getEndMillis() {
        return rawIntervals.length == 0 ? MAX_MILLIS : rawIntervals[rawIntervals.length - 1];
    }

    public long getStartMillis(final int index) {
        return rawIntervals[index << 1];
    }

    public long getEndMillis(final int index) {
        return rawIntervals[(index << 1) + 1];
    }

    public long[] getRawIntervals() {
        return rawIntervals;
    }

    public int getIntervalCount() {
        return rawIntervals.length >>> 1;
    }

    public int size() {
        return rawIntervals.length >>> 1;
    }

    /**
     * O(log N) Binary Search to find the index of the first interval that starts at or after the given time,
     * or {@code -1} if none exists. Zero heap allocations.
     */
    public int getFirstIntervalIndexStartingAfter(final long time) {
        final long[] raw = this.rawIntervals;
        final int n = raw.length >>> 1;
        if (n == 0 || raw[(n - 1) << 1] < time) return -1;
        if (raw[0] >= time) return 0;
        int low = 1;
        int high = n - 2;
        int resultIdx = n - 1;
        while (low <= high) {
            final int mid = (low + high) >>> 1;
            if (raw[mid << 1] >= time) {
                resultIdx = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return resultIdx;
    }

    /**
     * O(log N) Binary Search to find the first interval that starts at or after the given time.
     * Used for forward-sweeping temporal algorithms (Earliest Arrival / Earliest Departure).
     */
    public Interval getFirstIntervalStartingAfter(final long time) {
        final int resultIdx = getFirstIntervalIndexStartingAfter(time);
        return resultIdx < 0 ? null : new Interval(rawIntervals[resultIdx << 1], rawIntervals[(resultIdx << 1) + 1]);
    }

    /**
     * O(log N) Binary Search to find the index of the last interval that ends at or before the given time,
     * or {@code -1} if none exists. Zero heap allocations.
     */
    public int getLastIntervalIndexEndingBefore(final long time) {
        final long[] raw = this.rawIntervals;
        final int n = raw.length >>> 1;
        if (n == 0 || raw[1] > time) return -1;
        if (raw[((n - 1) << 1) + 1] <= time) return n - 1;
        int low = 1;
        int high = n - 2;
        int resultIdx = 0;
        while (low <= high) {
            final int mid = (low + high) >>> 1;
            if (raw[(mid << 1) + 1] <= time) {
                resultIdx = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return resultIdx;
    }

    /**
     * O(log N) Binary Search to find the last interval that ends at or before the given time.
     * Used for backward-sweeping temporal algorithms (Latest Arrival / Latest Departure).
     */
    public Interval getLastIntervalEndingBefore(final long time) {
        final int resultIdx = getLastIntervalIndexEndingBefore(time);
        return resultIdx < 0 ? null : new Interval(rawIntervals[resultIdx << 1], rawIntervals[(resultIdx << 1) + 1]);
    }

    public List<Interval> getIntervals() {
        List<Interval> res = this.intervals;
        if (res == null) {
            final int count = rawIntervals.length >>> 1;
            if (count == 0) {
                res = Collections.emptyList();
            } else {
                final List<Interval> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    list.add(new Interval(rawIntervals[i << 1], rawIntervals[(i << 1) + 1]));
                }
                res = Collections.unmodifiableList(list);
            }
            this.intervals = res;
        }
        return res;
    }

    public boolean isEmpty() {
        return rawIntervals.length == 0;
    }

    public boolean isUnbounded() {
        return this == UNBOUNDED ||
                (rawIntervals.length == 2 &&
                 rawIntervals[0] <= MIN_MILLIS &&
                 rawIntervals[1] >= MAX_MILLIS);
    }

    public static long toStartMillis(final Object value) {
        return (null == value) ? MIN_MILLIS : toMillis(value, "Start time");
    }

    public static long toEndMillis(final Object value) {
        return (null == value) ? MAX_MILLIS : toMillis(value, "End time");
    }

    public static long toTemporalMillis(final Object value) {
        if (null == value)
            throw new IllegalArgumentException("Temporal instant cannot be null");
        return toMillis(value, "Temporal instant");
    }

    public static Date toStartDate(final Object value) {
        return (null == value) ? new Date(MIN_MILLIS) : toDate(value, "Start time");
    }

    public static Date getStartTimeFromProperty(final Element element) {
        final Element base = unwrapElement(element);
        if (propertyOrEmpty(base, TEMPORAL_INTERVALS).isPresent())
            return fromProperties(base).getStartDate();
        final Property<Object> startTimeProperty = propertyOrEmpty(base, START_TIME);
        return toStartDate(startTimeProperty.orElse(null));
    }

    public static Date getEndTimeFromProperty(final Element element) {
        final Element base = unwrapElement(element);
        if (propertyOrEmpty(base, TEMPORAL_INTERVALS).isPresent())
            return fromProperties(base).getEndDate();
        final Property<Object> endTimeProperty = propertyOrEmpty(base, END_TIME);
        return toEndDate(endTimeProperty.orElse(null));
    }

    public static Date toEndDate(final Object value) {
        return (null == value) ? new Date(MAX_MILLIS) : toDate(value, "End time");
    }

    public static Date toTemporalDate(final Object value) {
        if (null == value)
            return null;

        return toDate(value, "Temporal instant");
    }

    private static long toMillis(final Object value, final String label) {
        if (value instanceof Number)
            return ((Number) value).longValue();

        if (value instanceof Date)
            return ((Date) value).getTime();

        if (value instanceof Instant)
            return ((Instant) value).toEpochMilli();

        if (value instanceof String) {
            final String dateString = ((String) value).trim();
            if (dateString.isEmpty())
                throw new IllegalArgumentException(label + " cannot be empty");

            try {
                return DatetimeHelper.parse(dateString).getTime();
            } catch (final RuntimeException e) {
                throw new IllegalArgumentException(label + " '" + value + "' is not a valid temporal value", e);
            }
        }

        throw new IllegalArgumentException(label + " value of type " + value.getClass().getName() +
                " is not a supported temporal value. Supported types are Date, Instant, Number, and String.");
    }

    private static Date toDate(final Object value, final String label) {
        if (value instanceof Date)
            return (Date) value;
        return new Date(toMillis(value, label));
    }

    private static Property<Object> propertyOrEmpty(final Element element, final String key) {
        if (element instanceof StarGraph.StarAdjacentVertex) {
            return Property.empty();
        }
        final Element target = element instanceof ComputerGraph.ComputerAdjacentVertex
                ? unwrapElement(element) : element;
        if (target instanceof StarGraph.StarAdjacentVertex) {
            return Property.empty();
        }
        final Property<Object> property = target.property(key);
        return null == property ? Property.empty() : property;
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object)
            return true;
        if (!(object instanceof Lifetime))
            return false;

        final Lifetime lifetime = (Lifetime) object;
        return Arrays.equals(this.rawIntervals, lifetime.rawIntervals);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.rawIntervals);
    }

    @Override
    public String toString() {
        final int count = rawIntervals.length >>> 1;
        final StringBuilder sb = new StringBuilder("lifetime[");
        for (int i = 0; i < count; i++) {
            sb.append(formatMillis(rawIntervals[i << 1])).append(',').append(formatMillis(rawIntervals[(i << 1) + 1]));
            if (i < count - 1) sb.append(" | ");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String formatMillis(final long millis) {
        return millis == MIN_MILLIS || millis == Long.MIN_VALUE ? "*" :
               millis == MAX_MILLIS || millis == Long.MAX_VALUE ? "*" :
               DatetimeHelper.format(Instant.ofEpochMilli(millis));
    }
}
