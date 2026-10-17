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
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * GraphBinary serializer for {@link Lifetime}.
 */
public class LifetimeSerializer extends SimpleTypeSerializer<Lifetime> {

    public LifetimeSerializer() {
        super(DataType.LIFETIME);
    }

    @Override
    protected Lifetime readValue(final Buffer buffer, final GraphBinaryReader context) throws IOException {
        final int size = buffer.readInt();
        final List<Lifetime.Interval> intervals = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final long start = buffer.readLong();
            final long end = buffer.readLong();
            intervals.add(new Lifetime.Interval(new Date(start), new Date(end)));
        }
        return Lifetime.from(intervals);
    }

    @Override
    protected void writeValue(final Lifetime value, final Buffer buffer, final GraphBinaryWriter context) throws IOException {
        final List<Lifetime.Interval> intervals = value.getIntervals();
        buffer.writeInt(intervals.size());
        for (final Lifetime.Interval interval : intervals) {
            buffer.writeLong(interval.getStart().getTime());
            buffer.writeLong(interval.getEnd().getTime());
        }
    }
}
