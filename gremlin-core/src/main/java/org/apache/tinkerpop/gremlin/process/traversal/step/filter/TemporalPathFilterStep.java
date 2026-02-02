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

import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Set;

/**
 * A FilterStep that validates temporal constraints for edges in a path.
 * Supports Continuous, Sequential, and Pairwise-Continuous patterns.
 */
public final class TemporalPathFilterStep<S> extends FilterStep<S> {

    public enum TemporalPathType {
        CONTINUOUS,
        SEQUENTIAL,
        PAIRWISE_CONTINUOUS
    }

    private final TemporalPathType type;
    private final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public TemporalPathFilterStep(final Traversal.Admin traversal, final TemporalPathType type) {
        super(traversal);
        this.type = type;
    }

    @Override
    protected boolean filter(final Traverser.Admin<S> traverser) {
        final Object object = traverser.get();
        if (!(object instanceof Edge)) {
            return true; // only filter edges
        }

        final Edge edge = (Edge) object;
        final Path path = traverser.path();

        final String edgeStartStr = getProperty(edge, "startTime");
        final String edgeEndStr = getProperty(edge, "endTime");

        if (edgeStartStr == null)
            return false;

        final LocalDateTime edgeStart = parseDateTime(edgeStartStr);
        final LocalDateTime edgeEnd = edgeEndStr != null ? parseDateTime(edgeEndStr) : LocalDateTime.MAX;

        switch (type) {
            case CONTINUOUS:
                return satisfiesContinuous(path, edge, edgeStart, edgeEnd);
            case SEQUENTIAL:
                return satisfiesSequential(path, edge, edgeStart, edgeEnd);
            case PAIRWISE_CONTINUOUS:
                return satisfiesPairwiseContinuous(path, edge, edgeStart, edgeEnd);
            default:
                return false;
        }
    }

    private boolean satisfiesContinuous(final Path path, final Edge currentEdge, final LocalDateTime edgeStart,
            final LocalDateTime edgeEnd) {
        // All edges in the path must overlap.
        LocalDateTime intersectStart = LocalDateTime.MIN;
        LocalDateTime intersectEnd = LocalDateTime.MAX;

        boolean foundOtherEdge = false;
        for (int i = 0; i < path.size(); i++) {
            final Object obj = path.get(i);
            if (obj instanceof Edge && obj != currentEdge) {
                foundOtherEdge = true;
                final Edge prevEdge = (Edge) obj;
                final String prevStartStr = getProperty(prevEdge, "startTime");
                if (prevStartStr == null)
                    continue;

                final LocalDateTime prevStart = parseDateTime(prevStartStr);
                final String prevEndStr = getProperty(prevEdge, "endTime");
                final LocalDateTime prevEnd = prevEndStr != null ? parseDateTime(prevEndStr) : LocalDateTime.MAX;

                // Update intersection
                intersectStart = intersectStart.isAfter(prevStart) ? intersectStart : prevStart;
                intersectEnd = intersectEnd.isBefore(prevEnd) ? intersectEnd : prevEnd;
            }
        }

        if (!foundOtherEdge)
            return true;

        // First, check if the previous edges themselves have a valid intersection
        if (intersectStart.isAfter(intersectEnd)) {
             // Depending on definition, single point intersection involves intersectStart == intersectEnd
             // isAfter returns false if equal, so [12:00, 12:00] is valid.
             return false;
        }

        // Now check intersection of 'current' with 'cumulative intersection'
        // We define "Continuous" as maintaining a non-empty intersection across all edges.
        // So we just intersect current with the running intersection and see if it's valid.
        
        LocalDateTime finalStart = intersectStart.isAfter(edgeStart) ? intersectStart : edgeStart;
        LocalDateTime finalEnd = intersectEnd.isBefore(edgeEnd) ? intersectEnd : edgeEnd;

        // If finalStart > finalEnd, intersection is empty (disjoint). 
        // If finalStart == finalEnd, it's a point intersection (allowed).
        return !finalStart.isAfter(finalEnd);
    }

    private boolean satisfiesSequential(final Path path, final Edge currentEdge, final LocalDateTime edgeStart,
            final LocalDateTime edgeEnd) {
        // Strict temporal ordering.
        Edge prevEdge = null;
        for (int i = path.size() - 1; i >= 0; i--) {
            final Object obj = path.get(i);
            if (obj instanceof Edge && obj != currentEdge) {
                prevEdge = (Edge) obj;
                break;
            }
        }

        if (prevEdge == null)
            return true;

        final String prevStartStr = getProperty(prevEdge, "startTime");
        // Should maybe fail? But sticking to permissive
        if (prevStartStr == null) return true; 
        
        final String prevEndStr = getProperty(prevEdge, "endTime");
        final LocalDateTime prevEnd = prevEndStr != null ? parseDateTime(prevEndStr) : LocalDateTime.MAX;
        final LocalDateTime prevStart = parseDateTime(prevStartStr);

        // Previous edge must be BEFORE or MEET current edge.
        // Equivalent to: prevEnd <= edgeStart
        // Using Allen: prev BEFORE curr OR prev MEETS curr
        return AllenFilterStep.evaluate(AllenFilterStep.AllenRelation.BEFORE, prevStart, prevEnd, edgeStart, edgeEnd) ||
               AllenFilterStep.evaluate(AllenFilterStep.AllenRelation.MEETS, prevStart, prevEnd, edgeStart, edgeEnd);
    }

    private boolean satisfiesPairwiseContinuous(final Path path, final Edge currentEdge, final LocalDateTime edgeStart,
            final LocalDateTime edgeEnd) {
        // Consecutive edges need to overlap (inclusive).
        Edge prevEdge = null;
        for (int i = path.size() - 1; i >= 0; i--) {
            final Object obj = path.get(i);
            if (obj instanceof Edge && obj != currentEdge) {
                prevEdge = (Edge) obj;
                break;
            }
        }

        if (prevEdge == null)
            return true;

        final String prevStartStr = getProperty(prevEdge, "startTime");
        if (prevStartStr == null)
            return true;

        final LocalDateTime prevStart = parseDateTime(prevStartStr);
        final String prevEndStr = getProperty(prevEdge, "endTime");
        final LocalDateTime prevEnd = prevEndStr != null ? parseDateTime(prevEndStr) : LocalDateTime.MAX;

        // "Work together" / "Overlap" means they are NOT Disjoint.
        // Disjoint = BEFORE or AFTER.
        // So we allow: OVERLAPS, MEETS, STARTS, FINISHES, EQUALS, DURING, CONTAINS, etc.
        boolean isBefore = AllenFilterStep.evaluate(AllenFilterStep.AllenRelation.BEFORE, prevStart, prevEnd, edgeStart, edgeEnd);
        boolean isAfter = AllenFilterStep.evaluate(AllenFilterStep.AllenRelation.AFTER, prevStart, prevEnd, edgeStart, edgeEnd);
        
        return !isBefore && !isAfter;
    }

    private String getProperty(final Edge edge, final String key) {
        try {
            return edge.property(key).isPresent() ? edge.property(key).value().toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseDateTime(final String str) {
        try {
            return LocalDateTime.parse(str, formatter);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(str);
            } catch (Exception e2) {
                return LocalDateTime.MIN;
            }
        }
    }

    @Override
    public String toString() {
        return StringFactory.stepString(this, this.type);
    }

    @Override
    public Set<TraverserRequirement> getRequirements() {
        return Collections.singleton(TraverserRequirement.PATH);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (type != null ? type.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) return true;
        if (!(other instanceof TemporalPathFilterStep)) return false;
        if (!super.equals(other)) return false;
        final TemporalPathFilterStep<?> that = (TemporalPathFilterStep<?>) other;
        return type == that.type;
    }
}
