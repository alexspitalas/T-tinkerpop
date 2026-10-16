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
package org.apache.tinkerpop.gremlin.util;

import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalElement;
import org.apache.tinkerpop.gremlin.structure.util.wrapped.WrappedEdge;

import java.util.Date;
import java.util.Optional;

public final class LifetimeHelper {

    private LifetimeHelper() {
    }

    public static boolean isAliveAt(final Element element, final Object instant) {
        return isAliveDuring(element, Lifetime.from(instant, instant));
    }

    /**
     * Returns {@code true} if the element is alive during the given window
     * (inclusive).
     * Edges are alive only when both incident vertices are also alive during the
     * window.
     */
    public static boolean isAliveDuring(final Element element, final Lifetime window) {
        if (!isElementAlive(element, window))
            return false;

        if (element instanceof Edge) {
            final Edge edge = unwrapEdge((Edge) element);
            if (edge != element && !isElementAlive(edge, window))
                return false;
            return isAliveDuring(edge.outVertex(), window)
                    && isAliveDuring(edge.inVertex(), window);
        }

        return true;
    }

    private static boolean isElementAlive(final Element element, final Lifetime window) {
        if (element instanceof TemporalElement) {
            final Lifetime lt = ((TemporalElement) element).getLifetime();
            if (lt != null) {
                return lt.isUnbounded() || intersects(lt, window);
            }
        }
        if (!Lifetime.hasLifetimeProperties(element)) {
            return true;
        }
        return intersects(Lifetime.fromProperties(element), window);
    }

    /**
     * Returns {@code true} if the lifetimes intersect inclusively. A single shared
     * point is an intersection.
     */
    public static boolean intersects(final Lifetime left, final Lifetime right) {
        return left.intersects(right);
    }

    /**
     * Returns the inclusive intersection of two lifetimes, or
     * {@link Optional#empty()} when they are disjoint.
     */
    public static Optional<Lifetime> intersection(final Lifetime left, final Lifetime right) {
        Lifetime intersect = left.intersection(right);
        if (intersect.isEmpty())
            return Optional.empty();
        return Optional.of(intersect);
    }

    /**
     * Returns {@code true} when {@code previous} ends before or exactly when
     * {@code current} starts.
     */
    public static boolean isSequential(final Lifetime previous, final Lifetime current) {
        return !previous.getEndDate().after(current.getStartDate());
    }

    private static Edge unwrapEdge(final Edge edge) {
        Edge current = edge;
        while (current instanceof WrappedEdge) {
            final Object baseEdge = ((WrappedEdge<?>) current).getBaseEdge();
            if (!(baseEdge instanceof Edge) || baseEdge == current)
                return current;
            current = (Edge) baseEdge;
        }
        return current;
    }

}
