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
package org.apache.tinkerpop.gremlin.process.traversal.step.filter;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.structure.Element;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * A filter step that implements all of Allen's temporal relationships.
 * This step compares temporal intervals between the current element and a reference element.
 * 
 * Optimizations applied:
 * 1. Lazy property access with caching for reference element
 * 2. Pre-parsed reference element temporal values
 * 3. Reusable DateTimeFormatter instances
 * 
 * Note: When AllenDecompositionStrategy is active, this step will be rewritten
 * into primitive where().by() chains for better performance.
 * 
 * @author Alex Spitalas
 */
public final class AllenFilterStep<S, E> extends FilterStep<S> {

    public enum AllenRelation {
        BEFORE("before"),
        AFTER("after"),
        MEETS("meets"),
        MET_BY("metBy"),
        OVERLAPS("overlaps"),
        OVERLAPPED_BY("overlappedBy"),
        STARTS("starts"),
        STARTED_BY("startedBy"),
        FINISHES("finishes"),
        FINISHED_BY("finishedBy"),
        DURING("during"),
        CONTAINS("contains"),
        EQUALS("equals");

        private final String value;

        AllenRelation(final String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static AllenRelation fromString(final String text) {
            for (AllenRelation relation : AllenRelation.values()) {
                if (relation.value.equalsIgnoreCase(text)) {
                    return relation;
                }
            }
            throw new IllegalArgumentException("Unknown Allen relation: " + text);
        }
    }

    private final AllenRelation relation;
    private final Element referenceElement;
    
    // Reusable formatter instances (thread-safe in Java 8+)
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter[] FALLBACK_FORMATTERS = {
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    };
    
    // Lazy caching of reference element temporal properties
    private transient String cachedRefStartTime = null;
    private transient String cachedRefEndTime = null;
    private transient LocalDateTime cachedRefStart = null;
    private transient LocalDateTime cachedRefEnd = null;
    private transient boolean refPropertiesInitialized = false;

    public AllenFilterStep(final Traversal.Admin<S, E> traversal, 
                          final AllenRelation relation, 
                          final Element referenceElement) {
        super(traversal);
        this.relation = relation;
        this.referenceElement = referenceElement;
    }

    @Override
    protected boolean filter(final Traverser.Admin<S> traverser) {
        final S currentObject = traverser.get();
        
        if (!(currentObject instanceof Element)) {
            return false;
        }
        
        final Element currentElement = (Element) currentObject;
        
        // Initialize reference properties once (lazy)
        if (!refPropertiesInitialized) {
            initializeReferenceProperties();
        }
        
        // Early exit if reference element has no temporal properties
        if (cachedRefStartTime == null) {
            return false;
        }
        
        // Get temporal properties from current element
        final String currentStartTime = getTemporalProperty(currentElement, "startTime");
        final String currentEndTime = getTemporalProperty(currentElement, "endTime");
        
        if (currentStartTime == null) {
            return false;
        }
        
        try {
            final LocalDateTime currentStart = parseDateTime(currentStartTime);
            final LocalDateTime currentEnd = currentEndTime != null ? 
                parseDateTime(currentEndTime) : LocalDateTime.MAX;
            
            return evaluate(relation, currentStart, currentEnd, cachedRefStart, cachedRefEnd);
            
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * Initialize and cache reference element temporal properties once.
     */
    private void initializeReferenceProperties() {
        cachedRefStartTime = getTemporalProperty(referenceElement, "startTime");
        cachedRefEndTime = getTemporalProperty(referenceElement, "endTime");
        
        if (cachedRefStartTime != null) {
            try {
                cachedRefStart = parseDateTime(cachedRefStartTime);
                cachedRefEnd = cachedRefEndTime != null ? 
                    parseDateTime(cachedRefEndTime) : LocalDateTime.MAX;
            } catch (DateTimeParseException e) {
                cachedRefStartTime = null;
                cachedRefStart = null;
                cachedRefEnd = null;
            }
        }
        
        refPropertiesInitialized = true;
    }

    /**
     * Static evaluation method for Allen relations.
     */
    public static boolean evaluate(final AllenRelation relation, 
                                   final LocalDateTime start1, final LocalDateTime end1,
                                   final LocalDateTime start2, final LocalDateTime end2) {
        switch (relation) {
            case BEFORE:
                return end1.isBefore(start2);
            case AFTER:
                return start1.isAfter(end2);
            case MEETS:
                return end1.equals(start2);
            case MET_BY:
                return start1.equals(end2);
            case OVERLAPS:
                return start1.isBefore(start2) && end1.isAfter(start2) && end1.isBefore(end2);
            case OVERLAPPED_BY:
                return start2.isBefore(start1) && end2.isAfter(start1) && end2.isBefore(end1);
            case STARTS:
                return start1.equals(start2) && end1.isBefore(end2);
            case STARTED_BY:
                return start1.equals(start2) && end1.isAfter(end2);
            case FINISHES:
                return start1.isAfter(start2) && end1.equals(end2);
            case FINISHED_BY:
                return start1.isBefore(start2) && end1.equals(end2);
            case DURING:
                return start1.isAfter(start2) && end1.isBefore(end2);
            case CONTAINS:
                return start1.isBefore(start2) && end1.isAfter(end2);
            case EQUALS:
                return start1.equals(start2) && end1.equals(end2);
            default:
                return false;
        }
    }

    private String getTemporalProperty(final Element element, final String propertyKey) {
        try {
            return element.property(propertyKey).isPresent() ? 
                element.property(propertyKey).value().toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseDateTime(final String dateTimeStr) throws DateTimeParseException {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            throw new DateTimeParseException("Empty datetime string", dateTimeStr, 0);
        }
        
        for (DateTimeFormatter formatter : FALLBACK_FORMATTERS) {
            try {
                return LocalDateTime.parse(dateTimeStr, formatter);
            } catch (DateTimeParseException e) {
                // Try next formatter
            }
        }
        
        return LocalDateTime.parse(dateTimeStr);
    }

    @Override
    public String toString() {
        return "AllenFilterStep(" + relation.getValue() + ", " + referenceElement + ")";
    }

    @Override
    public AllenFilterStep<S, E> clone() {
        final AllenFilterStep<S, E> clone = (AllenFilterStep<S, E>) super.clone();
        return clone;
    }

    // Getters required by AllenDecompositionStrategy
    public AllenRelation getRelation() {
        return relation;
    }

    public Element getReferenceElement() {
        return referenceElement;
    }
}
