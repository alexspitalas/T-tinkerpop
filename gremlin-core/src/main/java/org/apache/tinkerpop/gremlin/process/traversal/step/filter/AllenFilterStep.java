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

/**
 * A filter step that implements all of Allen's temporal relationships.
 * This step compares temporal intervals between the current element and a reference element.
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
    private final DateTimeFormatter formatter;

    public AllenFilterStep(final Traversal.Admin<S, E> traversal, final AllenRelation relation, final Element referenceElement) {
        super(traversal);
        this.relation = relation;
        this.referenceElement = referenceElement;
        this.formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    }

    @Override
    protected boolean filter(final Traverser.Admin<S> traverser) {
        final S currentObject = traverser.get();
        
        if (!(currentObject instanceof Element)) {
            return false;
        }
        
        final Element currentElement = (Element) currentObject;
        
        // Get temporal properties from current element
        final String currentStartTime = getTemporalProperty(currentElement, "startTime");
        final String currentEndTime = getTemporalProperty(currentElement, "endTime");
        
        // Get temporal properties from reference element
        final String refStartTime = getTemporalProperty(referenceElement, "startTime");
        final String refEndTime = getTemporalProperty(referenceElement, "endTime");
        
        // Both elements must have start times to be compared
        if (currentStartTime == null || refStartTime == null) {
            return false;
        }
        
        try {
            final LocalDateTime currentStart = parseDateTime(currentStartTime);
            final LocalDateTime currentEnd = currentEndTime != null ? 
                parseDateTime(currentEndTime) : LocalDateTime.MAX;
            
            final LocalDateTime refStart = parseDateTime(refStartTime);
            final LocalDateTime refEnd = refEndTime != null ? 
                parseDateTime(refEndTime) : LocalDateTime.MAX;
            
            return evaluate(relation, currentStart, currentEnd, refStart, refEnd);
            
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean evaluate(final AllenRelation relation, final LocalDateTime start1, final LocalDateTime end1,
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

    private LocalDateTime parseDateTime(final String dateTimeStr) {
        try {
            return LocalDateTime.parse(dateTimeStr, formatter);
        } catch (Exception e) {
            // Try with alternative formats if needed
            try {
                return LocalDateTime.parse(dateTimeStr);
            } catch (Exception e2) {
                throw new IllegalArgumentException("Cannot parse datetime: " + dateTimeStr, e2);
            }
        }
    }

    @Override
    public String toString() {
        return "AllenFilterStep(" + relation.getValue() + ", " + referenceElement + ")";
    }

    @Override
    public AllenFilterStep<S, E> clone() {
        return (AllenFilterStep<S, E>) super.clone();
    }

    public AllenRelation getRelation() {
        return relation;
    }

    public Element getReferenceElement() {
        return referenceElement;
    }
}