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
import org.apache.tinkerpop.gremlin.util.LifetimeHelper;

import java.util.Date;

/**
 * A filter step that implements all of Allen's temporal relationships.
 * This step compares temporal intervals between the current element and a reference element.
 * 
 * Optimizations applied:
 * 1. Lazy property access with caching for reference element
 * 2. Pre-parsed reference element temporal values
 * 3. Centralized lifetime value normalization
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
    
    // Lazy caching of reference element temporal properties
    private transient Object cachedRefStartTime = null;
    private transient Object cachedRefEndTime = null;
    private transient Date cachedRefStart = null;
    private transient Date cachedRefEnd = null;
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
        final Object currentStartTime = getTemporalProperty(currentElement, "startTime");
        final Object currentEndTime = getTemporalProperty(currentElement, "endTime");
        
        if (currentStartTime == null) {
            return false;
        }
        
        final Date currentStart = LifetimeHelper.toStartDate(currentStartTime);
        final Date currentEnd = LifetimeHelper.toEndDate(currentEndTime);

        return evaluate(relation, currentStart, currentEnd, cachedRefStart, cachedRefEnd);
    }

    /**
     * Initialize and cache reference element temporal properties once.
     */
    private void initializeReferenceProperties() {
        cachedRefStartTime = getTemporalProperty(referenceElement, "startTime");
        cachedRefEndTime = getTemporalProperty(referenceElement, "endTime");
        
        if (cachedRefStartTime != null) {
            cachedRefStart = LifetimeHelper.toStartDate(cachedRefStartTime);
            cachedRefEnd = LifetimeHelper.toEndDate(cachedRefEndTime);
        }
        
        refPropertiesInitialized = true;
    }

    /**
     * Static evaluation method for Allen relations.
     */
    public static boolean evaluate(final AllenRelation relation, 
                                   final Date start1, final Date end1,
                                   final Date start2, final Date end2) {
        switch (relation) {
            case BEFORE:
                return end1.before(start2);
            case AFTER:
                return start1.after(end2);
            case MEETS:
                return end1.equals(start2);
            case MET_BY:
                return start1.equals(end2);
            case OVERLAPS:
                return start1.before(start2) && end1.after(start2) && end1.before(end2);
            case OVERLAPPED_BY:
                return start2.before(start1) && end2.after(start1) && end2.before(end1);
            case STARTS:
                return start1.equals(start2) && end1.before(end2);
            case STARTED_BY:
                return start1.equals(start2) && end1.after(end2);
            case FINISHES:
                return start1.after(start2) && end1.equals(end2);
            case FINISHED_BY:
                return start1.before(start2) && end1.equals(end2);
            case DURING:
                return start1.after(start2) && end1.before(end2);
            case CONTAINS:
                return start1.before(start2) && end1.after(end2);
            case EQUALS:
                return start1.equals(start2) && end1.equals(end2);
            default:
                return false;
        }
    }

    private Object getTemporalProperty(final Element element, final String propertyKey) {
        try {
            return element.property(propertyKey).isPresent() ? 
                element.property(propertyKey).value() : null;
        } catch (Exception e) {
            return null;
        }
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
