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

import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.util.DatetimeHelper;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
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

    private static final long serialVersionUID = 3L;

    public static final String START_TIME = "startTime";
    public static final String END_TIME = "endTime";
    public static final String TEMPORAL_INTERVALS = "temporalIntervals";
    public static final long MAX_END_TIME = Long.MAX_VALUE;
    public static final long MIN_START_TIME = Long.MIN_VALUE;

    public static class Interval implements Serializable, Comparable<Interval> {
        private static final long serialVersionUID = 1L;

        private final long startTime;
        private final long endTime;

        public Interval(final long startTime, final long endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
            if (this.endTime < this.startTime)
                throw new IllegalArgumentException("End time must not be before start time");
        }

        public long getStartTime() { return startTime; }
        public long getEndTime() { return endTime; }

        public boolean contains(final long instant) {
            return instant >= startTime && instant <= endTime;
        }

        public boolean intersects(final Interval other) {
            return this.startTime <= other.endTime && this.endTime >= other.startTime;
        }

        public Interval intersection(final Interval other) {
            if (!intersects(other)) return null;
            return new Interval(Math.max(this.startTime, other.startTime), Math.min(this.endTime, other.endTime));
        }

        @Override
        public int compareTo(Interval other) {
            int c = Long.compare(this.startTime, other.startTime);
            if (c != 0) return c;
            return Long.compare(this.endTime, other.endTime);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Interval interval = (Interval) o;
            return startTime == interval.startTime && endTime == interval.endTime;
        }

        @Override
        public int hashCode() {
            return Objects.hash(startTime, endTime);
        }
    }

    private final List<Interval> intervals;

    private Lifetime(List<Interval> intervals) {
        this.intervals = Collections.unmodifiableList(normalize(intervals));
    }

    private Lifetime(final long startTime, final long endTime) {
        this(Collections.singletonList(new Interval(startTime, endTime)));
    }

    private static List<Interval> normalize(List<Interval> input) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        List<Interval> sorted = new ArrayList<>(input);
        Collections.sort(sorted);

        List<Interval> merged = new ArrayList<>();
        Interval current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            Interval next = sorted.get(i);
            if (current.getEndTime() >= next.getStartTime()) { // overlapping or adjacent
                current = new Interval(current.getStartTime(), Math.max(current.getEndTime(), next.getEndTime()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    public static Lifetime from(final Object startTime, final Object endTime) {
        return new Lifetime(toStartTime(startTime), toEndTime(endTime));
    }

    public static Lifetime from(List<Interval> intervals) {
        return new Lifetime(intervals);
    }

    public static Lifetime fromProperties(final Element element) {
        Property<Object> tempIntervalsProp = propertyOrEmpty(element, TEMPORAL_INTERVALS);
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

    private static Lifetime fromTemporalIntervalsString(final String value) {
        final List<Interval> parsedIntervals = new ArrayList<>();
        final String[] components = value.split(",", -1);
        for (String component : components) {
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
            parsedIntervals.add(new Interval(start, end));
        }
        return new Lifetime(parsedIntervals);
    }

    private static boolean isSignedDecimalInteger(final String value) {
        return value.matches("[+-]?[0-9]+");
    }

    private static IllegalArgumentException invalidTemporalIntervals(final String value, final String component) {
        return new IllegalArgumentException(
                "Invalid temporalIntervals component '" + component + "' in value '" + value + "'");
    }

    private String toTemporalIntervalsString() {
        StringBuilder sb = new StringBuilder();
        for (int i=0; i<intervals.size(); i++) {
            Interval iv = intervals.get(i);
            sb.append(iv.getStartTime()).append(":").append(iv.getEndTime());
            if (i < intervals.size() - 1) sb.append(",");
        }
        return sb.toString();
    }

    public static boolean hasLifetimeProperties(final Element element) {
        return propertyOrEmpty(element, START_TIME).isPresent() ||
                propertyOrEmpty(element, END_TIME).isPresent() ||
                propertyOrEmpty(element, TEMPORAL_INTERVALS).isPresent();
    }

    public static Lifetime getLifetimeFromProperties(final Element element) {
        return fromProperties(element);
    }

    public Object[] toProperties() {
        if (intervals.isEmpty()) return new Object[0];
        return new Object[] {
                START_TIME, getStartTime(),
                END_TIME, getEndTime(),
                TEMPORAL_INTERVALS, toTemporalIntervalsString()
        };
    }

    public Map<String, Object> toPropertyMap() {
        final Map<String, Object> properties = new LinkedHashMap<>();
        if (!intervals.isEmpty()) {
            properties.put(START_TIME, getStartTime());
            properties.put(END_TIME, getEndTime());
            properties.put(TEMPORAL_INTERVALS, toTemporalIntervalsString());
        }
        return properties;
    }

    public void attachTo(final Element element) {
        if (intervals.isEmpty()) {
            propertyOrEmpty(element, START_TIME).remove();
            propertyOrEmpty(element, END_TIME).remove();
            propertyOrEmpty(element, TEMPORAL_INTERVALS).remove();
            return;
        }
        element.property(START_TIME, getStartTime());
        element.property(END_TIME, getEndTime());
        element.property(TEMPORAL_INTERVALS, toTemporalIntervalsString());
    }

    public Lifetime addInterval(Object start, Object end) {
        List<Interval> newIntervals = new ArrayList<>(this.intervals);
        newIntervals.add(new Interval(toStartTime(start), toEndTime(end)));
        return new Lifetime(newIntervals);
    }

    public Lifetime dropInterval(Object start, Object end) {
        Interval drop = new Interval(toStartTime(start), toEndTime(end));
        List<Interval> newIntervals = new ArrayList<>();
        for (Interval current : this.intervals) {
            if (current.intersects(drop)) {
                if (current.getStartTime() < drop.getStartTime()) {
                    newIntervals.add(new Interval(current.getStartTime(), drop.getStartTime() - 1));
                }
                if (current.getEndTime() > drop.getEndTime()) {
                    newIntervals.add(new Interval(drop.getEndTime() + 1, current.getEndTime()));
                }
            } else {
                newIntervals.add(current);
            }
        }
        return new Lifetime(newIntervals);
    }

    public Lifetime intersection(Lifetime window) {
        List<Interval> result = new ArrayList<>();
        for (Interval myInt : this.intervals) {
            for (Interval winInt : window.intervals) {
                Interval intersect = myInt.intersection(winInt);
                if (intersect != null) {
                    result.add(intersect);
                }
            }
        }
        return new Lifetime(result);
    }

    public boolean contains(final long instant) {
        for (Interval interval : intervals) {
            if (interval.contains(instant)) return true;
            if (interval.getStartTime() > instant) break;
        }
        return false;
    }

    public boolean intersects(Lifetime window) {
        for (Interval myInt : this.intervals) {
            for (Interval winInt : window.intervals) {
                if (myInt.intersects(winInt)) return true;
            }
        }
        return false;
    }

    public boolean contains(final Lifetime other) {
        Lifetime intersect = this.intersection(other);
        return intersect.equals(other);
    }

    public long getStartTime() {
        if (intervals.isEmpty()) return MIN_START_TIME;
        return intervals.get(0).getStartTime();
    }

    public long getEndTime() {
        if (intervals.isEmpty()) return MAX_END_TIME;
        return intervals.get(intervals.size() - 1).getEndTime();
    }

    public List<Interval> getIntervals() {
        return intervals;
    }

    public boolean isEmpty() {
        return intervals.isEmpty();
    }

    public static long toStartTime(final Object value) {
        return null == value ? MIN_START_TIME : toTime(value, "Start time");
    }

    public static long getStartTimeFromProperty(final Element element) {
        if (propertyOrEmpty(element, TEMPORAL_INTERVALS).isPresent())
            return fromProperties(element).getStartTime();
        final Property<Object> startTimeProperty = propertyOrEmpty(element, START_TIME);
        return toStartTime(startTimeProperty.orElse(null));
    }

    public static long getEndTimeFromProperty(final Element element) {
        if (propertyOrEmpty(element, TEMPORAL_INTERVALS).isPresent())
            return fromProperties(element).getEndTime();
        final Property<Object> endTimeProperty = propertyOrEmpty(element, END_TIME);
        return toEndTime(endTimeProperty.orElse(null));
    }

    public static long toEndTime(final Object value) {
        return null == value ? MAX_END_TIME : toTime(value, "End time");
    }

    public static Long toTemporalTime(final Object value) {
        if (null == value)
            return null;

        return toTime(value, "Temporal instant");
    }

    private static long toTime(final Object value, final String label) {
        if (value instanceof Date)
            return ((Date) value).getTime();

        if (value instanceof Instant)
            return ((Instant) value).toEpochMilli();

        if (value instanceof Number)
            return ((Number) value).longValue();

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

    private static Property<Object> propertyOrEmpty(final Element element, final String key) {
        final Property<Object> property = element.property(key);
        return null == property ? Property.empty() : property;
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object)
            return true;
        if (!(object instanceof Lifetime))
            return false;

        final Lifetime lifetime = (Lifetime) object;
        return Objects.equals(this.intervals, lifetime.intervals);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.intervals);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("lifetime[");
        for (int i=0; i<intervals.size(); i++) {
            Interval iv = intervals.get(i);
            sb.append(formatTime(iv.getStartTime())).append(",").append(formatTime(iv.getEndTime()));
            if (i < intervals.size() - 1) sb.append(" | ");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatTime(final long time) {
        return time == MIN_START_TIME || time == MAX_END_TIME ? "*" :
               DatetimeHelper.format(Instant.ofEpochMilli(time));
    }
}
