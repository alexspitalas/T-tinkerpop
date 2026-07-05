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
package org.apache.tinkerpop.gremlin.util;

import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;

import java.time.Instant;
import java.util.Date;

public final class LifetimeHelper {

    public static final String DEFAULT_ENDTIME = "1e10";

    private LifetimeHelper() {
    }

    public static Date getStartDateProperty(final Element element) {
        final Object value = getPropertyValue(element, "startTime");
        return null == value ? null : toStartDate(value);
    }

    public static Date getEndDateProperty(final Element element) {
        return toEndDate(getPropertyValue(element, "endTime"));
    }

    public static Date toStartDate(final Object value) {
        if (null == value)
            throw new IllegalArgumentException("Start time cannot be null");

        return toDate(value, "Start time");
    }

    public static Date toEndDate(final Object value) {
        if (null == value)
            return new Date(Long.MAX_VALUE);

        if (value instanceof String && DEFAULT_ENDTIME.equals(((String) value).trim()))
            return new Date(Long.MAX_VALUE);

        return toDate(value, "End time");
    }

    /**
     * Converts a temporal instant value to a {@link Date} for use with {@code atTime}.
     * Accepts the same types as {@link #toStartDate}: {@link Date}, {@link java.time.Instant},
     * {@link Number} (epoch millis), or {@link String} (ISO-8601 / parseable date string).
     *
     * @throws IllegalArgumentException if the value is null or of an unsupported type
     */
    public static Date toTemporalDate(final Object value) {
        if (null == value)
            throw new IllegalArgumentException("Temporal instant cannot be null");
        return toDate(value, "Temporal instant");
    }

    /**
     * Returns {@code true} if the element is alive at the given instant.
     *
     * <ul>
     *   <li>If {@code startTime} is absent, the element is treated as always alive
     *       (open-world assumption).</li>
     *   <li>If {@code endTime} is absent, it defaults to {@link Long#MAX_VALUE}
     *       (the element is still active).</li>
     * </ul>
     */
    public static boolean isAliveAt(final Element element, final Date instant) {
        final Date start = getStartDateProperty(element);
        final Date end = getEndDateProperty(element);
        
        if (start != null && start.after(instant)) return false;
        return !end.before(instant);
    }

    /**
     * Convenience overload that converts the instant before checking.
     *
     * @throws IllegalArgumentException if instant is null or of an unsupported type
     */
    public static boolean isAliveAt(final Element element, final Object instant) {
        return isAliveAt(element, toTemporalDate(instant));
    }

    /**
     * Returns {@code true} if the element's lifetime intersects with the given window (inclusive).
     */
    public static boolean isAliveDuring(final Element element, final Date windowStart, final Date windowEnd) {
        if (windowEnd == null)
            return isAliveAt(element, windowStart);

        final Date start = getStartDateProperty(element);
        final Date end = getEndDateProperty(element);
        
        // elementStart <= windowEnd AND elementEnd >= windowStart
        if (start != null && start.after(windowEnd)) return false;
        return !end.before(windowStart);
    }

    /**
     * Convenience overload that converts the bounds before checking.
     */
    public static boolean isAliveDuring(final Element element, final Object windowStart, final Object windowEnd) {
        return isAliveDuring(element, toTemporalDate(windowStart), windowEnd == null ? null : toTemporalDate(windowEnd));
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

    private static Object getPropertyValue(final Element element, final String key) {
        try {
            final Property<Object> property = element.property(key);
            return property.isPresent() ? property.value() : null;
        } catch (final RuntimeException e) {
            return null;
        }
    }
}
