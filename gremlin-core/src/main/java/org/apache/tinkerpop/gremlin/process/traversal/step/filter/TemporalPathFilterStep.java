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
import org.apache.tinkerpop.gremlin.process.traversal.Temporal;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.util.LifetimeHelper;

import java.util.Collections;
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
        // Fast path tracks a single interval [interStart, interEnd] in primitives with zero heap allocations
        // while all edges have 1 interval (N = 1), falling back to Lifetime.intersection only when N > 1.
        boolean hasPrev = false;
        long interStart = 0L;
        long interEnd = 0L;
        Lifetime multiIntersection = null;

        for (int i = 0; i < path.size(); i++) {
            final Object obj = path.get(i);
            final boolean isTrailingCurrent = (i == path.size() - 1 && obj == currentEdge);
            if (obj instanceof Edge && !isTrailingCurrent) {
                final Lifetime prevLifetime = LifetimeHelper.resolveEffectiveLifetime((Edge) obj);
                if (prevLifetime.isEmpty()) {
                    return false;
                }
                final long[] raw = prevLifetime.getRawIntervals();
                if (!hasPrev) {
                    hasPrev = true;
                    if (raw.length == 2) {
                        interStart = raw[0];
                        interEnd = raw[1];
                    } else {
                        multiIntersection = prevLifetime;
                    }
                } else if (multiIntersection == null && raw.length == 2) {
                    if (!Temporal.intersects.testIntervalPair(interStart, interEnd, raw[0], raw[1])) {
                        return false;
                    }
                    interStart = Math.max(interStart, raw[0]);
                    interEnd = Math.min(interEnd, raw[1]);
                } else {
                    if (multiIntersection == null) {
                        multiIntersection = prevLifetime.intersection(Lifetime.from(interStart, interEnd));
                    } else {
                        multiIntersection = multiIntersection.intersection(prevLifetime);
                    }
                    if (multiIntersection.isEmpty()) {
                        return false;
                    }
                }
            }
        }

        if (!hasPrev) {
            return true;
        }
        if (multiIntersection != null) {
            return multiIntersection.intersects(edgeLifetime);
        }
        final long[] curRaw = edgeLifetime.getRawIntervals();
        if (curRaw.length == 2) {
            return Temporal.intersects.testIntervalPair(interStart, interEnd, curRaw[0], curRaw[1]);
        }
        return edgeLifetime.intersects(interStart, interEnd);
    }

    private boolean satisfiesSequential(final Path path, final Edge currentEdge, final Lifetime edgeLifetime) {
        if (maxDelay == Long.MAX_VALUE) {
            return satisfiesSequentialUnbounded(path, currentEdge, edgeLifetime);
        }
        return satisfiesSequentialBounded(path, currentEdge, edgeLifetime);
    }

    /**
     * When {@code maxDelay == Long.MAX_VALUE}, only the earliest-ending reachable interval on each hop
     * is needed: in a normalized {@link Lifetime}, intervals are sorted by start and non-overlapping
     * with non-decreasing end times, so the first interval starting at or after {@code prevEnd + minDelay}
     * dominates all later intervals for both causality and {@code delay >= minDelay}.
     */
    private boolean satisfiesSequentialUnbounded(final Path path, final Edge currentEdge, final Lifetime edgeLifetime) {
        boolean hasPrev = false;
        long earliestEnd = 0L;
        for (int i = 0; i < path.size(); i++) {
            final Object obj = path.get(i);
            final boolean isTrailingCurrent = (i == path.size() - 1 && obj == currentEdge);
            if (obj instanceof Edge && !isTrailingCurrent) {
                final Lifetime lt = LifetimeHelper.resolveEffectiveLifetime((Edge) obj);
                if (lt.isEmpty()) {
                    return false;
                }
                if (!hasPrev) {
                    earliestEnd = lt.getEndMillis(0);
                    hasPrev = true;
                } else {
                    final int idx = findFirstSequentialIntervalIndex(lt, earliestEnd);
                    if (idx < 0) {
                        return false;
                    }
                    earliestEnd = lt.getEndMillis(idx);
                }
            }
        }

        if (!hasPrev) {
            return true;
        }
        return findFirstSequentialIntervalIndex(edgeLifetime, earliestEnd) >= 0;
    }

    private int findFirstSequentialIntervalIndex(final Lifetime lt, final long prevEnd) {
        if (minDelay <= 0L) {
            return lt.getFirstIntervalIndexStartingAfter(prevEnd);
        }
        if (prevEnd > Long.MAX_VALUE - minDelay) {
            return -1;
        }
        return lt.getFirstIntervalIndexStartingAfter(prevEnd + minDelay);
    }

    /**
     * When {@code maxDelay < Long.MAX_VALUE}, an earlier-ending interval can fail {@code maxDelay}
     * on a later hop while a later-ending interval on the same edge succeeds, so all reachable intervals
     * must be retained and advanced via a linear two-pointer window.
     */
    private boolean satisfiesSequentialBounded(final Path path, final Edge currentEdge, final Lifetime edgeLifetime) {
        if (maxDelay < 0L || minDelay > maxDelay) {
            return hasNoPrecedingEdge(path, currentEdge);
        }
        final long effMinDelay = Math.max(0L, minDelay);
        long[] prevRaw = null;
        int prevPairs = 0;

        for (int i = 0; i < path.size(); i++) {
            final Object obj = path.get(i);
            final boolean isTrailingCurrent = (i == path.size() - 1 && obj == currentEdge);
            if (obj instanceof Edge && !isTrailingCurrent) {
                final Lifetime lt = LifetimeHelper.resolveEffectiveLifetime((Edge) obj);
                if (lt.isEmpty()) {
                    return false;
                }
                final long[] raw = lt.getRawIntervals();
                final int count = raw.length >>> 1;
                if (prevRaw == null) {
                    prevRaw = raw;
                    prevPairs = count;
                } else if (count == 1) {
                    if (advanceSequentialRaw(prevRaw, prevPairs, raw, 1, effMinDelay, maxDelay, null) == 0) {
                        return false;
                    }
                    prevRaw = raw;
                    prevPairs = 1;
                } else {
                    final long[] nextRaw = new long[raw.length];
                    prevPairs = advanceSequentialRaw(prevRaw, prevPairs, raw, count, effMinDelay, maxDelay, nextRaw);
                    if (prevPairs == 0) {
                        return false;
                    }
                    prevRaw = nextRaw;
                }
            }
        }

        if (prevRaw == null) {
            return true;
        }
        final long[] currentRaw = edgeLifetime.getRawIntervals();
        return advanceSequentialRaw(prevRaw, prevPairs, currentRaw, currentRaw.length >>> 1, effMinDelay, maxDelay, null) > 0;
    }

    private static boolean hasNoPrecedingEdge(final Path path, final Edge currentEdge) {
        for (int i = 0; i < path.size(); i++) {
            final Object obj = path.get(i);
            final boolean isTrailingCurrent = (i == path.size() - 1 && obj == currentEdge);
            if (obj instanceof Edge && !isTrailingCurrent) {
                return false;
            }
        }
        return true;
    }

    private static int advanceSequentialRaw(final long[] prevRaw, final int prevPairs,
                                            final long[] nextRaw, final int nextPairs,
                                            final long effMinDelay, final long maxDelay,
                                            final long[] outRaw) {
        int outPairs = 0;
        int p = 0;
        for (int j = 0; j < nextPairs; j++) {
            final long nextStart = nextRaw[j << 1];
            // Long.compareUnsigned handles signed difference overflow when prevEnd <= nextStart.
            while (p < prevPairs && prevRaw[(p << 1) + 1] <= nextStart
                    && Long.compareUnsigned(nextStart - prevRaw[(p << 1) + 1], maxDelay) > 0) {
                p++;
            }
            if (p == prevPairs) {
                break;
            }
            final long prevEnd = prevRaw[(p << 1) + 1];
            if (prevEnd <= nextStart && Long.compareUnsigned(nextStart - prevEnd, effMinDelay) >= 0) {
                if (outRaw == null) {
                    return 1;
                }
                final int idx = outPairs << 1;
                outRaw[idx] = nextStart;
                outRaw[idx + 1] = nextRaw[(j << 1) + 1];
                outPairs++;
            }
        }
        return outPairs;
    }

    private boolean satisfiesPairwiseContinuous(final Path path, final Edge currentEdge, final Lifetime edgeLifetime) {
        // Consecutive edges need to overlap along a connected chain of reachable sub-intervals.
        long[] prevRaw = null;
        int prevPairs = 0;
        for (int i = 0; i < path.size(); i++) {
            final Object obj = path.get(i);
            final boolean isTrailingCurrent = (i == path.size() - 1 && obj == currentEdge);
            if (obj instanceof Edge && !isTrailingCurrent) {
                final Lifetime lt = LifetimeHelper.resolveEffectiveLifetime((Edge) obj);
                if (lt.isEmpty()) {
                    return false;
                }
                final long[] raw = lt.getRawIntervals();
                final int count = raw.length >>> 1;
                if (prevRaw == null) {
                    prevRaw = raw;
                    prevPairs = count;
                } else if (count == 1) {
                    if (advancePairwiseRaw(prevRaw, prevPairs, raw, 1, null) == 0) {
                        return false;
                    }
                    prevRaw = raw;
                    prevPairs = 1;
                } else {
                    final long[] nextRaw = new long[raw.length];
                    prevPairs = advancePairwiseRaw(prevRaw, prevPairs, raw, count, nextRaw);
                    if (prevPairs == 0) {
                        return false;
                    }
                    prevRaw = nextRaw;
                }
            }
        }

        if (prevRaw == null) {
            return true;
        }
        final long[] currentRaw = edgeLifetime.getRawIntervals();
        return advancePairwiseRaw(prevRaw, prevPairs, currentRaw, currentRaw.length >>> 1, null) > 0;
    }

    private int advancePairwiseRaw(final long[] prevRaw, final int prevPairs,
                                   final long[] nextRaw, final int nextPairs,
                                   final long[] outRaw) {
        int outPairs = 0;
        int p = 0;
        for (int j = 0; j < nextPairs; j++) {
            final long nextStart = nextRaw[j << 1];
            final long nextEnd = nextRaw[(j << 1) + 1];
            while (p < prevPairs && prevRaw[(p << 1) + 1] < nextStart) {
                p++;
            }
            if (p == prevPairs) {
                break;
            }
            for (int k = p; k < prevPairs; k++) {
                final long prevStart = prevRaw[k << 1];
                if (prevStart > nextEnd || (monotone && prevStart > nextStart)) {
                    break;
                }
                final long prevEnd = prevRaw[(k << 1) + 1];
                if (Temporal.intersects.testIntervalPair(prevStart, prevEnd, nextStart, nextEnd)) {
                    if (outRaw == null) {
                        return 1;
                    }
                    final int idx = outPairs << 1;
                    outRaw[idx] = nextStart;
                    outRaw[idx + 1] = nextEnd;
                    outPairs++;
                    break;
                }
            }
        }
        return outPairs;
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
