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
package org.apache.tinkerpop.gremlin.process.traversal.step.util;

import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

public final class LifetimeHelper {

    public static final String START_TIME = "startTime";
    public static final String END_TIME = "endTime";
    public static final String DEFAULT_ENDTIME = "1e10";

    private static final String[] DATE_FORMATS = {
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy",
            "dd-MM-yyyy HH:mm:ss",
            "dd-MM-yyyy",
            "MM/dd/yyyy HH:mm:ss",
            "MM/dd/yyyy",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
    };

    private LifetimeHelper() {
    }

    public static String getEndTimeOrDefault(final String endTime) {
        return null == endTime ? DEFAULT_ENDTIME : endTime;
    }

    public static void validateTimeWindow(final String startTime, final String endTime) {
        if (null == startTime || startTime.trim().isEmpty()) {
            throw new IllegalArgumentException("Start time cannot be null or empty");
        }

        if (null == endTime || endTime.trim().isEmpty()) {
            throw new IllegalArgumentException("End time cannot be null or empty");
        }

        final Date startDate = parseDate(startTime);
        final Date endDate = parseDate(endTime);

        if (null == startDate) {
            throw new IllegalArgumentException("Start time '" + startTime + "' is not in a valid date format. Supported formats: " + Arrays.toString(DATE_FORMATS));
        }

        if (null == endDate) {
            throw new IllegalArgumentException("End time '" + endTime + "' is not in a valid date format. Supported formats: " + Arrays.toString(DATE_FORMATS));
        }

        if (!startDate.before(endDate)) {
            throw new IllegalArgumentException("Start time (" + startTime + ") must be before end time (" + endTime + ")");
        }
    }

    public static boolean timeRangesOverlap(final String start1, final String end1, final String start2, final String end2) {
        try {
            final Date start1Date = parseDate(start1);
            final Date end1Date = parseDate(end1);
            final Date start2Date = parseDate(start2);
            final Date end2Date = parseDate(end2);

            if (null == start1Date || null == end1Date || null == start2Date || null == end2Date) {
                return true;
            }

            return !start1Date.after(end2Date) && !start2Date.after(end1Date);
        } catch (final Exception ignored) {
            return true;
        }
    }

    public static boolean isActive(final Element element, final String queryStartTime, final String queryEndTime) {
        if (null == queryStartTime) {
            return true;
        }

        final String elementStartTime = getStartTime(element);
        final String elementEndTime = getEndTime(element);

        if (null == elementStartTime && null == elementEndTime) {
            return true;
        }

        final String normalizedElementStartTime = null == elementStartTime ? String.valueOf(Long.MIN_VALUE) : elementStartTime;
        return timeRangesOverlap(
                normalizedElementStartTime,
                getEndTimeOrDefault(elementEndTime),
                queryStartTime,
                getEndTimeOrDefault(queryEndTime));
    }

    public static String getStartTime(final Element element) {
        final Property<String> property = element.property(START_TIME);
        return property.isPresent() ? property.value() : null;
    }

    public static String getEndTime(final Element element) {
        final Property<String> property = element.property(END_TIME);
        return property.isPresent() ? property.value() : null;
    }

    private static Date parseDate(final String dateString) {
        if (null == dateString) {
            return null;
        }

        if (DEFAULT_ENDTIME.equals(dateString)) {
            return new Date(Long.MAX_VALUE);
        }

        try {
            return new Date(Long.parseLong(dateString));
        } catch (final NumberFormatException ignored) {
        }

        for (final String format : DATE_FORMATS) {
            try {
                final SimpleDateFormat sdf = new SimpleDateFormat(format);
                sdf.setLenient(false);
                final Date parsedDate = sdf.parse(dateString);
                if (!dateString.equals(sdf.format(parsedDate))) {
                    continue;
                }
                return parsedDate;
            } catch (final ParseException ignored) {
            }
        }

        return null;
    }
}
