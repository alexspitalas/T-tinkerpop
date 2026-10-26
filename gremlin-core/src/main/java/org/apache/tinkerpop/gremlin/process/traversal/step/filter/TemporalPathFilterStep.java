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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
        final Lifetime edgeLifetime = LifetimeHelper.resolveEffectiveLifetime(edge);
        if (edgeLifetime.isEmpty()) {
            return false;
        }

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
            final boolean isTrailingCurrent = (i == path.size() - 1 && obj == currentEdge);
            if (obj instanceof Edge && !isTrailingCurrent) {
                foundOtherEdge = true;
                final Edge prevEdge = (Edge) obj;
                final Lifetime prevLifetime = LifetimeHelper.resolveEffectiveLifetime(prevEdge);
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
        // Strict temporal ordering across single or multiple intervals.
        List<Lifetime.Interval> reachable = null;
        for (int i = 0; i < path.size(); i++) {
            final Object obj = path.get(i);
            final boolean isTrailingCurrent = (i == path.size() - 1 && obj == currentEdge);
            if (obj instanceof Edge && !isTrailingCurrent) {
                final Lifetime lt = LifetimeHelper.resolveEffectiveLifetime((Edge) obj);
                if (lt.isEmpty())
                    return false;
                if (reachable == null) {
                    reachable = lt.getIntervals();
                } else {
                    reachable = advanceSequentialIntervals(reachable, lt.getIntervals());
                    if (reachable.isEmpty())
                        return false;
                }
            }
        }

        if (reachable == null)
            return true;

        return !advanceSequentialIntervals(reachable, edgeLifetime.getIntervals()).isEmpty();
    }

    private List<Lifetime.Interval> advanceSequentialIntervals(final List<Lifetime.Interval> prevIntervals,
                                                               final List<Lifetime.Interval> nextIntervals) {
        final List<Lifetime.Interval> validNext = new ArrayList<>();
        for (final Lifetime.Interval nextIv : nextIntervals) {
            final long nextStart = nextIv.getStart().getTime();
            for (final Lifetime.Interval prevIv : prevIntervals) {
                final long prevEnd = prevIv.getEnd().getTime();
                if (prevEnd <= nextStart) {
                    final long delay = nextStart - prevEnd;
                    if (delay >= minDelay && delay <= maxDelay) {
                        validNext.add(nextIv);
                        break;
                    }
                }
            }
        }
        return validNext;
    }

    private boolean satisfiesPairwiseContinuous(final Path path, final Edge currentEdge, final Lifetime edgeLifetime) {
        // Consecutive edges need to overlap.
        if (!monotone) {
            Edge prevEdge = null;
            for (int i = path.size() - 1; i >= 0; i--) {
                final Object obj = path.get(i);
                final boolean isTrailingCurrent = (i == path.size() - 1 && obj == currentEdge);
                if (obj instanceof Edge && !isTrailingCurrent) {
                    prevEdge = (Edge) obj;
                    break;
                }
            }

            if (prevEdge == null)
                return true;

            final Lifetime prevLifetime = LifetimeHelper.resolveEffectiveLifetime(prevEdge);
            return LifetimeHelper.intersects(prevLifetime, edgeLifetime);
        }

        List<Lifetime.Interval> reachable = null;
        for (int i = 0; i < path.size(); i++) {
            final Object obj = path.get(i);
            final boolean isTrailingCurrent = (i == path.size() - 1 && obj == currentEdge);
            if (obj instanceof Edge && !isTrailingCurrent) {
                final Lifetime lt = LifetimeHelper.resolveEffectiveLifetime((Edge) obj);
                if (lt.isEmpty())
                    return false;
                if (reachable == null) {
                    reachable = lt.getIntervals();
                } else {
                    reachable = advanceMonotonePairwiseIntervals(reachable, lt.getIntervals());
                    if (reachable.isEmpty())
                        return false;
                }
            }
        }

        if (reachable == null)
            return true;

        return !advanceMonotonePairwiseIntervals(reachable, edgeLifetime.getIntervals()).isEmpty();
    }

    private List<Lifetime.Interval> advanceMonotonePairwiseIntervals(final List<Lifetime.Interval> prevIntervals,
                                                                     final List<Lifetime.Interval> nextIntervals) {
        final List<Lifetime.Interval> validNext = new ArrayList<>();
        for (final Lifetime.Interval nextIv : nextIntervals) {
            for (final Lifetime.Interval prevIv : prevIntervals) {
                if (prevIv.intersects(nextIv) && !nextIv.getStart().before(prevIv.getStart())) {
                    validNext.add(nextIv);
                    break;
                }
            }
        }
        return validNext;
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
