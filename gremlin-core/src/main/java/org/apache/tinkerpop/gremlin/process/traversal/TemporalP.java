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

package org.apache.tinkerpop.gremlin.process.traversal;



/**
 * Predefined {@code Predicate} values that can be used as temporal filters in pattern matching.
 */
public class TemporalP extends P<Object> {

    public TemporalP(final PBiPredicate<Object, Object> biPredicate, final Object value) {
        super(biPredicate, value);
    }

    public static TemporalP intersects(final Object value) { return new TemporalP(Temporal.intersects, value); }
    public static TemporalP overlaps(final Object value) { return new TemporalP(Temporal.overlaps, value); }
    public static TemporalP precedes(final Object value) { return new TemporalP(Temporal.precedes, value); }
    public static TemporalP succeeds(final Object value) { return new TemporalP(Temporal.succeeds, value); }
    public static TemporalP overlappedBy(final Object value) { return new TemporalP(Temporal.overlappedBy, value); }
    public static TemporalP meets(final Object value) { return new TemporalP(Temporal.meets, value); }
    public static TemporalP metBy(final Object value) { return new TemporalP(Temporal.metBy, value); }
    public static TemporalP during(final Object value) { return new TemporalP(Temporal.during, value); }
    public static TemporalP contains(final Object value) { return new TemporalP(Temporal.contains, value); }
    public static TemporalP eq(final Object value) { return new TemporalP(Temporal.eq, value); }
    public static TemporalP starts(final Object value) { return new TemporalP(Temporal.starts, value); }
    public static TemporalP startedBy(final Object value) { return new TemporalP(Temporal.startedBy, value); }
    public static TemporalP finishes(final Object value) { return new TemporalP(Temporal.finishes, value); }
    public static TemporalP finishedBy(final Object value) { return new TemporalP(Temporal.finishedBy, value); }

    @Override
    public TemporalP clone() {
        return (TemporalP) super.clone();
    }
}
