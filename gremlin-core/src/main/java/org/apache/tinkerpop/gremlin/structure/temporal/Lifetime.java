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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class Lifetime implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Date startDate;
    private final Date endDate;


    public static final String START_TIME = "startTime";
    public static final String END_TIME = "endTime";
    public static final String MAX_END_TIME = "+292278994-08-17T07:12:55.807Z";
    public static final String MIN_START_TIME = "-292275055-05-16T16:47:04.192Z";

    private Lifetime(final Date startDate, final Date endDate) {

        this.startDate = new Date(Objects.requireNonNull(startDate, "startDate cannot be null").getTime());
        this.endDate = new Date(Objects.requireNonNull(endDate, "endDate cannot be null").getTime());

        if (this.endDate.before(this.startDate))
            throw new IllegalArgumentException("End time must not be before start time");
    }

    public static Lifetime from(final Object startDate, final Object endDate) {
        return new Lifetime(toStartDate(startDate), toEndDate(endDate));
    }

    public static Lifetime fromProperties(final Element element) {
        final Property<Object> startTimeProperty = element.property(START_TIME);
        final Property<Object> endTimeProperty = element.property(END_TIME);

        return from(startTimeProperty.orElse(null), endTimeProperty.orElse(null));
    }

    public static boolean hasLifetimeProperties(final Element element) {
        return element.property(START_TIME).isPresent() || element.property(END_TIME).isPresent();
    }

    public static Lifetime getLifetimeFromProperties(final Element element) {
        return fromProperties(element);
    }

    public Object[] toProperties() {
        return new Object[] {
                START_TIME, getStartDate(),
                END_TIME, getEndDate()
        };
    }

    public Map<String, Object> toPropertyMap() {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(START_TIME, getStartDate());
        properties.put(END_TIME, getEndDate());
        return properties;
    }

    public void attachTo(final Element element) {
        element.property(START_TIME, getStartDate());
        element.property(END_TIME, getEndDate());
    }

    public boolean contains(final Lifetime other) {
        return !other.startDate.before(this.startDate) && !other.endDate.after(this.endDate);
    }

    public Date getStartDate() {
        return new Date(startDate.getTime());
    }

    public Date getEndDate() {
        return new Date(endDate.getTime());
    }

    public static Date toStartDate(final Object value) {
        return (null == value)? toDate(MIN_START_TIME, "Start time"): toDate(value, "Start time");
    }

    public static Date getStartTimeFromProperty(final Element element) {
        final Property<Object> startTimeProperty = element.property(START_TIME);
        return toStartDate(startTimeProperty.orElse(null));
    }

    public static Date getEndTimeFromProperty(final Element element) {
        final Property<Object> endTimeProperty = element.property(END_TIME);
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

    @Override
    public boolean equals(final Object object) {
        if (this == object)
            return true;
        if (!(object instanceof Lifetime))
            return false;

        final Lifetime lifetime = (Lifetime) object;
        return Objects.equals(this.startDate, lifetime.startDate) &&
                Objects.equals(this.endDate, lifetime.endDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.startDate, this.endDate);
    }

    @Override
    public String toString() {
        return "lifetime[" + formatStartDate() + "," + formatEndDate() + "]";
    }

    private String formatStartDate() {
        return startDate.getTime() == Long.MIN_VALUE ? "*" :
                DatetimeHelper.format(startDate.toInstant());
    }

    private String formatEndDate() {
        return endDate.getTime() == Long.MAX_VALUE ? "*" :
                DatetimeHelper.format(endDate.toInstant());
    }
}
