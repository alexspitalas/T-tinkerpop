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
 * A filter step that implements Allen's temporal "equals" relationship.
 * Two temporal intervals are equal if they have identical start and end times.
 */
public final class TemporalEqualsStep<S, E> extends FilterStep<S> {

    private final Element referenceElement;
    private final DateTimeFormatter formatter;

    public TemporalEqualsStep(final Traversal.Admin<S, E> traversal, final Element referenceElement) {
        super(traversal);
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
            
            // Allen temporal equals: intervals have identical start and end times
            return currentStart.equals(refStart) && currentEnd.equals(refEnd);
            
        } catch (Exception e) {
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
        return "TemporalEqualsStep(" + referenceElement + ")";
    }

    @Override
    public TemporalEqualsStep<S, E> clone() {
        return (TemporalEqualsStep<S, E>) super.clone();
    }

    public Element getReferenceElement() {
        return referenceElement;
    }
}