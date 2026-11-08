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
package org.apache.tinkerpop.gremlin.structure.io.binary.types;

import org.apache.tinkerpop.gremlin.structure.io.Buffer;
import org.apache.tinkerpop.gremlin.structure.io.binary.DataType;
import org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryReader;
import org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryWriter;
import org.apache.tinkerpop.gremlin.structure.temporal.GeneralizedIntervalRelation;
import org.apache.tinkerpop.gremlin.structure.temporal.GeneralizedRelation;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;

import java.io.IOException;

/**
 * GraphBinary serializer for {@link Lifetime} and related temporal types.
 */
public class LifetimeSerializer extends SimpleTypeSerializer<Lifetime> {

    public LifetimeSerializer() {
        super(DataType.LIFETIME);
    }

    @Override
    protected Lifetime readValue(final Buffer buffer, final GraphBinaryReader context) throws IOException {
        final int size = buffer.readInt();
        final long[] raw = new long[size << 1];
        for (int i = 0; i < size; i++) {
            raw[i << 1] = buffer.readLong();
            raw[(i << 1) + 1] = buffer.readLong();
        }
        return Lifetime.fromRawIntervals(raw);
    }

    @Override
    protected void writeValue(final Lifetime value, final Buffer buffer, final GraphBinaryWriter context) throws IOException {
        final int count = value.getIntervalCount();
        buffer.writeInt(count);
        for (int i = 0; i < count; i++) {
            buffer.writeLong(value.getStartMillis(i));
            buffer.writeLong(value.getEndMillis(i));
        }
    }

    public static class IntervalSerializer extends SimpleTypeSerializer<Lifetime.Interval> {
        public IntervalSerializer() {
            super(DataType.INTERVAL);
        }

        @Override
        protected Lifetime.Interval readValue(final Buffer buffer, final GraphBinaryReader context) throws IOException {
            return new Lifetime.Interval(buffer.readLong(), buffer.readLong());
        }

        @Override
        protected void writeValue(final Lifetime.Interval value, final Buffer buffer, final GraphBinaryWriter context) throws IOException {
            buffer.writeLong(value.getStartMillis());
            buffer.writeLong(value.getEndMillis());
        }
    }

    public static class GeneralizedRelationSerializer extends SimpleTypeSerializer<GeneralizedRelation> {
        public GeneralizedRelationSerializer() {
            super(DataType.GENERALIZEDRELATION);
        }

        @Override
        protected GeneralizedRelation readValue(final Buffer buffer, final GraphBinaryReader context) throws IOException {
            final int p = buffer.readInt();
            final int q = buffer.readInt();
            final int[] zones = new int[p];
            for (int i = 0; i < p; i++) {
                zones[i] = buffer.readInt();
            }
            return new GeneralizedRelation(p, q, zones);
        }

        @Override
        protected void writeValue(final GeneralizedRelation value, final Buffer buffer, final GraphBinaryWriter context) throws IOException {
            buffer.writeInt(value.getP());
            buffer.writeInt(value.getQ());
            for (int i = 1; i <= value.getP(); i++) {
                buffer.writeInt(value.getZone(i));
            }
        }
    }

    public static class GeneralizedIntervalRelationSerializer extends SimpleTypeSerializer<GeneralizedIntervalRelation> {
        public GeneralizedIntervalRelationSerializer() {
            super(DataType.GENERALIZEDINTERVALRELATION);
        }

        @Override
        protected GeneralizedIntervalRelation readValue(final Buffer buffer, final GraphBinaryReader context) throws IOException {
            final int p = buffer.readInt();
            final int q = buffer.readInt();
            final boolean empty = buffer.readBoolean();
            if (empty) {
                return GeneralizedIntervalRelation.empty(p, q);
            }
            final int[] lowerZones = new int[p];
            for (int i = 0; i < p; i++) {
                lowerZones[i] = buffer.readInt();
            }
            final int[] upperZones = new int[p];
            for (int i = 0; i < p; i++) {
                upperZones[i] = buffer.readInt();
            }
            return new GeneralizedIntervalRelation(
                    new GeneralizedRelation(p, q, lowerZones),
                    new GeneralizedRelation(p, q, upperZones));
        }

        @Override
        protected void writeValue(final GeneralizedIntervalRelation value, final Buffer buffer, final GraphBinaryWriter context) throws IOException {
            buffer.writeInt(value.getP());
            buffer.writeInt(value.getQ());
            buffer.writeBoolean(value.isEmpty());
            if (!value.isEmpty()) {
                for (int i = 1; i <= value.getP(); i++) {
                    buffer.writeInt(value.getLower().getZone(i));
                }
                for (int i = 1; i <= value.getP(); i++) {
                    buffer.writeInt(value.getUpper().getZone(i));
                }
            }
        }
    }
}
