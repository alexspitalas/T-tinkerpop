/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.traversal.step.util;

import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Arrays;

/**
 * Utility class implementing Allen's 13 temporal interval relationships for graph traversal.
 * Based on Allen's interval algebra from "Maintaining Knowledge about Temporal Intervals" (1983).
 * 
 * The 13 relations are:
 * - before/after: X before Y means X ends before Y starts
 * - meets/metBy: X meets Y means X ends exactly when Y starts  
 * - overlaps/overlappedBy: X overlaps Y means X starts before Y, ends after Y starts but before Y ends
 * - starts/startedBy: X starts Y means X and Y start at same time, X ends before Y
 * - finishes/finishedBy: X finishes Y means X starts after Y starts, X and Y end at same time
 * - during/contains: X during Y means X starts after Y starts and ends before Y ends
 * - equals: X equals Y means X and Y have identical start and end times
 *
 * @author TinkerPop Temporal Extension
 */
public class AllenOperators {

    /**
     * Enumeration of Allen's 13 temporal relationships
     */
    public enum AllenRelation {
        BEFORE, AFTER, MEETS, MET_BY, OVERLAPS, OVERLAPPED_BY, 
        STARTS, STARTED_BY, FINISHES, FINISHED_BY, 
        DURING, CONTAINS, EQUALS
    }

    // Common date formats to try for parsing
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

    private static final String DEFAULT_ENDTIME = "1e10";

    /**
     * Parse a date string using multiple format attempts
     */
    private static Date parseDate(String dateString) {
        if (DEFAULT_ENDTIME.equals(dateString)) {
            return new Date(Long.MAX_VALUE);
        }
        
        // Try parsing as timestamp
        try {
            long timestamp = Long.parseLong(dateString);
            return new Date(timestamp);
        } catch (NumberFormatException e) {
            // Continue with date format parsing
        }
        
        // Try each date format
        for (String format : DATE_FORMATS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(format);
                sdf.setLenient(false);
                Date parsedDate = sdf.parse(dateString);
                
                // Validate that the parsed date matches the original string
                String formattedBack = sdf.format(parsedDate);
                if (dateString.equals(formattedBack)) {
                    return parsedDate;
                }
            } catch (ParseException e) {
                // Try next format
            }
        }
        
        return null;
    }

    /**
     * Extract temporal interval from an element (start time, end time)
     */
    public static TemporalInterval getTemporalInterval(Element element) {
        Property<?> startProp = element.property("startTime");
        Property<?> endProp = element.property("endTime");
        
        if (!startProp.isPresent() || !endProp.isPresent()) {
            return null; // Element has no temporal properties
        }
        
        String startTimeStr = String.valueOf(startProp.value());
        String endTimeStr = String.valueOf(endProp.value());
        
        Date startTime = parseDate(startTimeStr);
        Date endTime = parseDate(endTimeStr);
        
        if (startTime == null || endTime == null) {
            return null; // Could not parse temporal properties
        }
        
        return new TemporalInterval(startTime, endTime);
    }

    /**
     * Test if interval X has the specified Allen relation with interval Y
     */
    public static boolean testAllenRelation(TemporalInterval x, TemporalInterval y, AllenRelation relation) {
        if (x == null || y == null) {
            return false;
        }
        
        Date xs = x.getStart();
        Date xe = x.getEnd();
        Date ys = y.getStart();
        Date ye = y.getEnd();
        
        switch (relation) {
            case BEFORE:
                return xe.before(ys);
                
            case AFTER:
                return xs.after(ye);
                
            case MEETS:
                return xe.equals(ys);
                
            case MET_BY:
                return xs.equals(ye);
                
            case OVERLAPS:
                return xs.before(ys) && xe.after(ys) && xe.before(ye);
                
            case OVERLAPPED_BY:
                return ys.before(xs) && ye.after(xs) && ye.before(xe);
                
            case STARTS:
                return xs.equals(ys) && xe.before(ye);
                
            case STARTED_BY:
                return xs.equals(ys) && xe.after(ye);
                
            case FINISHES:
                return xs.after(ys) && xe.equals(ye);
                
            case FINISHED_BY:
                return xs.before(ys) && xe.equals(ye);
                
            case DURING:
                return xs.after(ys) && xe.before(ye);
                
            case CONTAINS:
                return xs.before(ys) && xe.after(ye);
                
            case EQUALS:
                return xs.equals(ys) && xe.equals(ye);
                
            default:
                return false;
        }
    }

    /**
     * Test if element X has the specified Allen relation with element Y
     */
    public static boolean testAllenRelation(Element x, Element y, AllenRelation relation) {
        TemporalInterval intervalX = getTemporalInterval(x);
        TemporalInterval intervalY = getTemporalInterval(y);
        
        return testAllenRelation(intervalX, intervalY, relation);
    }

    /**
     * Get the Allen relation that holds between intervals X and Y
     * Note: Only one relation should be true for valid intervals
     */
    public static AllenRelation getAllenRelation(TemporalInterval x, TemporalInterval y) {
        for (AllenRelation relation : AllenRelation.values()) {
            if (testAllenRelation(x, y, relation)) {
                return relation;
            }
        }
        return null; // No valid relation found
    }

    /**
     * Inner class representing a temporal interval
     */
    public static class TemporalInterval {
        private final Date start;
        private final Date end;
        
        public TemporalInterval(Date start, Date end) {
            if (start.after(end)) {
                throw new IllegalArgumentException("Start time must be before or equal to end time");
            }
            this.start = start;
            this.end = end;
        }
        
        public Date getStart() {
            return start;
        }
        
        public Date getEnd() {
            return end;
        }
        
        @Override
        public String toString() {
            return "[" + start + ", " + end + "]";
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            
            TemporalInterval that = (TemporalInterval) o;
            return start.equals(that.start) && end.equals(that.end);
        }
        
        @Override
        public int hashCode() {
            return start.hashCode() * 31 + end.hashCode();
        }
    }
}