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
