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

    private static final long serialVersionUID = 2L;

    public static final String START_TIME = "startTime";
    public static final String END_TIME = "endTime";
    public static final String TEMPORAL_INTERVALS = "temporalIntervals";
    public static final String MAX_END_TIME = "+292278994-08-17T07:12:55.807Z";
    public static final String MIN_START_TIME = "-292275055-05-16T16:47:04.192Z";

    public static class Interval implements Serializable, Comparable<Interval> {
        private final Date start;
        private final Date end;

        public Interval(final Date start, final Date end) {
            this.start = new Date(Objects.requireNonNull(start, "start cannot be null").getTime());
            this.end = new Date(Objects.requireNonNull(end, "end cannot be null").getTime());
            if (this.end.before(this.start))
                throw new IllegalArgumentException("End time must not be before start time");
        }

        public Date getStart() { return new Date(start.getTime()); }
        public Date getEnd() { return new Date(end.getTime()); }

        public boolean contains(Date instant) {
            return !instant.before(start) && !instant.after(end);
        }

        public boolean intersects(Interval other) {
            return !this.start.after(other.end) && !this.end.before(other.start);
        }

        public Interval intersection(Interval other) {
            if (!intersects(other)) return null;
            Date maxStart = this.start.after(other.start) ? this.start : other.start;
            Date minEnd = this.end.before(other.end) ? this.end : other.end;
            return new Interval(maxStart, minEnd);
        }

        @Override
        public int compareTo(Interval other) {
            int c = this.start.compareTo(other.start);
            if (c != 0) return c;
            return this.end.compareTo(other.end);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Interval interval = (Interval) o;
            return start.equals(interval.start) && end.equals(interval.end);
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end);
        }
    }

    private final List<Interval> intervals;

    private Lifetime(List<Interval> intervals) {
        this.intervals = Collections.unmodifiableList(normalize(intervals));
    }

    private Lifetime(final Date startDate, final Date endDate) {
        this(Collections.singletonList(new Interval(startDate, endDate)));
    }

    private static List<Interval> normalize(List<Interval> input) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        List<Interval> sorted = new ArrayList<>(input);
        Collections.sort(sorted);

        List<Interval> merged = new ArrayList<>();
        Interval current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            Interval next = sorted.get(i);
            if (!current.getEnd().before(next.getStart())) { // overlapping or adjacent
                Date maxEnd = current.getEnd().after(next.getEnd()) ? current.getEnd() : next.getEnd();
                current = new Interval(current.getStart(), maxEnd);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    public static Lifetime from(final Object startDate, final Object endDate) {
        return new Lifetime(toStartDate(startDate), toEndDate(endDate));
    }

    public static Lifetime from(List<Interval> intervals) {
        return new Lifetime(intervals);
    }

    public static Lifetime fromProperties(final Element element) {
        Property<Object> tempIntervalsProp = propertyOrEmpty(element, TEMPORAL_INTERVALS);
        if (tempIntervalsProp.isPresent()) {
            return fromTemporalIntervalsString(tempIntervalsProp.value().toString());
        }
        final Property<Object> startTimeProperty = propertyOrEmpty(element, START_TIME);
        final Property<Object> endTimeProperty = propertyOrEmpty(element, END_TIME);
        return from(startTimeProperty.orElse(null), endTimeProperty.orElse(null));
    }

    private static Lifetime fromTemporalIntervalsString(String val) {
        List<Interval> list = new ArrayList<>();
        String[] parts = val.split(",");
        for (String p : parts) {
            String[] se = p.split(":");
            if (se.length == 2) {
                list.add(new Interval(new Date(Long.parseLong(se[0])), new Date(Long.parseLong(se[1]))));
            }
        }
        return new Lifetime(list);
    }

    private String toTemporalIntervalsString() {
        StringBuilder sb = new StringBuilder();
        for (int i=0; i<intervals.size(); i++) {
            Interval iv = intervals.get(i);
            sb.append(iv.getStart().getTime()).append(":").append(iv.getEnd().getTime());
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
                START_TIME, getStartDate(),
                END_TIME, getEndDate(),
                TEMPORAL_INTERVALS, toTemporalIntervalsString()
        };
    }

    public Map<String, Object> toPropertyMap() {
        final Map<String, Object> properties = new LinkedHashMap<>();
        if (!intervals.isEmpty()) {
            properties.put(START_TIME, getStartDate());
            properties.put(END_TIME, getEndDate());
            properties.put(TEMPORAL_INTERVALS, toTemporalIntervalsString());
        }
        return properties;
    }

    public void attachTo(final Element element) {
        if (intervals.isEmpty()) {
            element.property(START_TIME).remove();
            element.property(END_TIME).remove();
            element.property(TEMPORAL_INTERVALS).remove();
            return;
        }
        element.property(START_TIME, getStartDate());
        element.property(END_TIME, getEndDate());
        element.property(TEMPORAL_INTERVALS, toTemporalIntervalsString());
    }

    public Lifetime addInterval(Object start, Object end) {
        List<Interval> newIntervals = new ArrayList<>(this.intervals);
        newIntervals.add(new Interval(toStartDate(start), toEndDate(end)));
        return new Lifetime(newIntervals);
    }

    public Lifetime dropInterval(Object start, Object end) {
        Interval drop = new Interval(toStartDate(start), toEndDate(end));
        List<Interval> newIntervals = new ArrayList<>();
        for (Interval current : this.intervals) {
            if (current.intersects(drop)) {
                if (current.getStart().before(drop.getStart())) {
                    newIntervals.add(new Interval(current.getStart(), new Date(drop.getStart().getTime() - 1)));
                }
                if (current.getEnd().after(drop.getEnd())) {
                    newIntervals.add(new Interval(new Date(drop.getEnd().getTime() + 1), current.getEnd()));
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

    public boolean contains(Date instant) {
        for (Interval interval : intervals) {
            if (interval.contains(instant)) return true;
            if (interval.getStart().after(instant)) break;
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

    public Date getStartDate() {
        if (intervals.isEmpty()) return toStartDate(null);
        return intervals.get(0).getStart();
    }

    public Date getEndDate() {
        if (intervals.isEmpty()) return toEndDate(null);
        return intervals.get(intervals.size() - 1).getEnd();
    }

    public List<Interval> getIntervals() {
        return intervals;
    }

    public boolean isEmpty() {
        return intervals.isEmpty();
    }

    public static Date toStartDate(final Object value) {
        return (null == value)? toDate(MIN_START_TIME, "Start time"): toDate(value, "Start time");
    }

    public static Date getStartTimeFromProperty(final Element element) {
        final Property<Object> startTimeProperty = propertyOrEmpty(element, START_TIME);
        return toStartDate(startTimeProperty.orElse(null));
    }

    public static Date getEndTimeFromProperty(final Element element) {
        final Property<Object> endTimeProperty = propertyOrEmpty(element, END_TIME);
        return toEndDate(endTimeProperty.orElse(null));
    }

    public static Date toEndDate(final Object value) {
        return (null == value)? toDate(MAX_END_TIME, "End time"): toDate(value, "End time");
    }

    public static Date toTemporalDate(final Object value) {
        if (null == value)
            return null;

        return toDate(value, "Temporal instant");
    }

    private static Date toDate(final Object value, final String label) {
        if (value instanceof Date)
            return (Date) value;

        if (value instanceof Instant)
            return Date.from((Instant) value);

        if (value instanceof Number)
            return new Date(((Number) value).longValue());

        if (value instanceof String) {
            final String dateString = ((String) value).trim();
            if (dateString.isEmpty())
                throw new IllegalArgumentException(label + " cannot be empty");

            try {
                return DatetimeHelper.parse(dateString);
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
            sb.append(formatDate(iv.getStart())).append(",").append(formatDate(iv.getEnd()));
            if (i < intervals.size() - 1) sb.append(" | ");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatDate(Date date) {
        return date.getTime() == Long.MIN_VALUE ? "*" :
               date.getTime() == Long.MAX_VALUE ? "*" :
               DatetimeHelper.format(date.toInstant());
    }
}
