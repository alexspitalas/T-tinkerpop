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
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.util.LifetimeHelper;

import java.util.Collections;
import java.util.Date;
import java.util.Optional;
import java.util.Set;

/**
 * A FilterStep that validates temporal constraints for edges in a path.
 * Supports Continuous, Sequential, and Pairwise-Continuous patterns.
 * 
 * @author Alex Spitalas
 */
public final class TemporalPathFilterStep<S> extends FilterStep<S> {

    public enum TemporalPathType {
        CONTINUOUS,
        SEQUENTIAL,
        PAIRWISE_CONTINUOUS
    }

    private final TemporalPathType type;
    private final long minDelay;
    private final long maxDelay;
    private final boolean monotone;

    public TemporalPathFilterStep(final Traversal.Admin traversal, final TemporalPathType type) {
        this(traversal, type, 0, Long.MAX_VALUE, false);
    }

    public TemporalPathFilterStep(final Traversal.Admin traversal, final TemporalPathType type, final long minDelay, final long maxDelay, final boolean monotone) {
        super(traversal);
        this.type = type;
        this.minDelay = minDelay;
        this.maxDelay = maxDelay;
        this.monotone = monotone;
    }

    @Override
    protected boolean filter(final Traverser.Admin<S> traverser) {
        final Object object = traverser.get();
        if (!(object instanceof Edge)) {
            return true; // only filter edges
        }

        final Edge edge = (Edge) object;
        final Path path = traverser.path();
        final Lifetime edgeLifetime = Lifetime.fromProperties(edge);

        switch (type) {
            case CONTINUOUS:
                return satisfiesContinuous(path, edge, edgeLifetime);
            case SEQUENTIAL:
                return satisfiesSequential(path, edge, edgeLifetime);
            case PAIRWISE_CONTINUOUS:
                return satisfiesPairwiseContinuous(path, edge, edgeLifetime);
            default:
                return false;
        }
    }

    private boolean satisfiesContinuous(final Path path, final Edge currentEdge, final Lifetime edgeLifetime) {
        // All edges in the path must overlap.
        Optional<Lifetime> intersection = Optional.of(Lifetime.from(null, null));

        boolean foundOtherEdge = false;
        for (int i = 0; i < path.size(); i++) {
            final Object obj = path.get(i);
            if (obj instanceof Edge && obj != currentEdge) {
                foundOtherEdge = true;
                final Edge prevEdge = (Edge) obj;
                final Lifetime prevLifetime = Lifetime.fromProperties(prevEdge);
                intersection = LifetimeHelper.intersection(intersection.get(), prevLifetime);
                if (!intersection.isPresent())
                    return false;
            }
        }

        if (!foundOtherEdge)
            return true;

        return LifetimeHelper.intersection(intersection.get(), edgeLifetime).isPresent();
    }

    private boolean satisfiesSequential(final Path path, final Edge currentEdge, final Lifetime edgeLifetime) {
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

        final Lifetime prevLifetime = Lifetime.fromProperties(prevEdge);
        final Date prevEnd = prevLifetime.getEndDate();
        final Date edgeStart = edgeLifetime.getStartDate();

        // Previous edge must be BEFORE or MEET current edge.
        if (!LifetimeHelper.isSequential(prevLifetime, edgeLifetime)) return false;

        // Check Min/Max delay
        if (minDelay > 0 || maxDelay < Long.MAX_VALUE) {
            final long delay = edgeStart.getTime() - prevEnd.getTime();
            if (delay < minDelay) return false;
            if (delay > maxDelay) return false;
        }

        return true;
    }

    private boolean satisfiesPairwiseContinuous(final Path path, final Edge currentEdge, final Lifetime edgeLifetime) {
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

        final Lifetime prevLifetime = Lifetime.fromProperties(prevEdge);
        final Date prevStart = prevLifetime.getStartDate();

        if (!LifetimeHelper.intersects(prevLifetime, edgeLifetime)) return false;

        if (monotone) {
            // Monotone: current edge start >= previous edge start
            if (edgeLifetime.getStartDate().before(prevStart)) return false;
        }

        return true;
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
        result = 31 * result + (int) (minDelay ^ (minDelay >>> 32));
        result = 31 * result + (int) (maxDelay ^ (maxDelay >>> 32));
        result = 31 * result + (monotone ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) return true;
        if (!(other instanceof TemporalPathFilterStep)) return false;
        if (!super.equals(other)) return false;
        final TemporalPathFilterStep<?> that = (TemporalPathFilterStep<?>) other;
        return type == that.type && minDelay == that.minDelay && maxDelay == that.maxDelay && monotone == that.monotone;
    }
}
