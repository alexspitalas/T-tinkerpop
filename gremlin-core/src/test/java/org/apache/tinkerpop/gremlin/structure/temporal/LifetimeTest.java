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
package org.apache.tinkerpop.gremlin.structure.temporal;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.util.Serializer;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LifetimeTest {

    @Test
    public void shouldParseSentinelValues() {
        assertEquals(Long.MIN_VALUE, Lifetime.toStartDate(Lifetime.MIN_START_TIME).getTime());
        assertEquals(Long.MAX_VALUE, Lifetime.toEndDate(Lifetime.MAX_END_TIME).getTime());
    }

    @Test
    public void shouldAcceptSupportedTemporalValueTypes() {
        assertLifetime(1000L, 2000L, Lifetime.from(new Date(1000L), new Date(2000L)));
        assertLifetime(1000L, 2000L, Lifetime.from(Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(2000L)));
        assertLifetime(1000L, 2000L, Lifetime.from(1000L, 2000));
        assertLifetime(1000L, 2000L, Lifetime.from("1970-01-01T00:00:01Z", "1970-01-01T00:00:02Z"));
    }

    @Test
    public void shouldRejectInvalidTemporalValues() {
        assertThrows(IllegalArgumentException.class, () -> Lifetime.from("", 2000L));
        assertThrows(IllegalArgumentException.class, () -> Lifetime.from("   ", 2000L));
        assertThrows(IllegalArgumentException.class, () -> Lifetime.from("not-a-date", 2000L));
        assertThrows(IllegalArgumentException.class, () -> Lifetime.from(new Object(), 2000L));
        assertThrows(IllegalArgumentException.class, () -> Lifetime.from(2000L, 1000L));
    }

    @Test
    public void shouldDefensivelyCopyDateValues() {
        final Date startTime = new Date(1000L);
        final Date endTime = new Date(2000L);
        final Lifetime lifetime = Lifetime.from(startTime, endTime);

        startTime.setTime(3000L);
        endTime.setTime(4000L);

        assertLifetime(1000L, 2000L, lifetime);

        final Date returnedStartTime = lifetime.getStartDate();
        final Date returnedEndTime = lifetime.getEndDate();

        returnedStartTime.setTime(3000L);
        returnedEndTime.setTime(4000L);

        assertLifetime(1000L, 2000L, lifetime);
        assertNotSame(returnedStartTime, lifetime.getStartDate());
        assertNotSame(returnedEndTime, lifetime.getEndDate());
    }

    @Test
    public void shouldCompareByStartAndEndTime() {
        final Lifetime lifetime = Lifetime.from(1000L, 2000L);
        final Lifetime equalLifetime = Lifetime.from(new Date(1000L), new Date(2000L));

        assertEquals(lifetime, equalLifetime);
        assertEquals(lifetime.hashCode(), equalLifetime.hashCode());
        assertNotEquals(lifetime, Lifetime.from(999L, 2000L));
        assertNotEquals(lifetime, Lifetime.from(1000L, 2001L));
        assertFalse(lifetime.equals("not a lifetime"));
        assertEquals("lifetime[1970-01-01T00:00:01Z,1970-01-01T00:00:02Z]", lifetime.toString());
    }

    @Test
    public void shouldFormatOpenEndedBounds() {
        assertEquals("lifetime[*,1970-01-01T00:00:02Z]", Lifetime.from(new Date(Long.MIN_VALUE), new Date(2000L)).toString());
        assertEquals("lifetime[1970-01-01T00:00:01Z,*]", Lifetime.from(new Date(1000L), new Date(Long.MAX_VALUE)).toString());
        assertEquals("lifetime[*,*]", Lifetime.from(new Date(Long.MIN_VALUE), new Date(Long.MAX_VALUE)).toString());
    }

    @Test
    public void shouldReadLifetimeFromProperties() {
        final Element element = elementWithProperties(new Date(1000L), new Date(2000L));

        final Lifetime lifetime = Lifetime.fromProperties(element);

        assertLifetime(1000L, 2000L, lifetime);
        assertEquals(new Date(1000L), Lifetime.getStartTimeFromProperty(element));
        assertEquals(new Date(2000L), Lifetime.getEndTimeFromProperty(element));
    }

    @Test
    public void shouldReadExtentFromTemporalIntervals() {
        final Element element = elementWithProperties(new Date(5000L), new Date(6000L));
        final Property<Object> intervalsProperty = property("1000:2000,3000:4000");
        when(element.property(Lifetime.TEMPORAL_INTERVALS)).thenReturn(intervalsProperty);

        assertEquals(new Date(1000L), Lifetime.getStartTimeFromProperty(element));
        assertEquals(new Date(4000L), Lifetime.getEndTimeFromProperty(element));

        final Element intervalsOnly = elementWithTemporalIntervals("1000:2000,3000:4000");
        assertEquals(new Date(1000L), Lifetime.getStartTimeFromProperty(intervalsOnly));
        assertEquals(new Date(4000L), Lifetime.getEndTimeFromProperty(intervalsOnly));
    }

    @Test
    public void shouldParseValidTemporalIntervalsProperty() {
        assertEquals(Arrays.asList(
                        new Lifetime.Interval(new Date(1000L), new Date(2000L)),
                        new Lifetime.Interval(new Date(3000L), new Date(4000L))),
                Lifetime.fromProperties(elementWithTemporalIntervals("1000:2000,3000:4000")).getIntervals());
        assertEquals(Arrays.asList(
                        new Lifetime.Interval(new Date(-2000L), new Date(-1000L))),
                Lifetime.fromProperties(elementWithTemporalIntervals("-2000:-1000")).getIntervals());
        assertLifetime(Long.MIN_VALUE, Long.MAX_VALUE,
                Lifetime.fromProperties(elementWithTemporalIntervals(
                        Long.MIN_VALUE + ":" + Long.MAX_VALUE)));
    }

    @Test
    public void shouldNormalizeValidTemporalIntervalsProperty() {
        final Lifetime lifetime = Lifetime.fromProperties(
                elementWithTemporalIntervals("3000:5000,1000:2000,1500:4000"));

        assertEquals(Arrays.asList(
                new Lifetime.Interval(new Date(1000L), new Date(5000L))), lifetime.getIntervals());
        assertEquals("1000:5000", lifetime.toPropertyMap().get(Lifetime.TEMPORAL_INTERVALS));
    }

    @Test
    public void shouldRejectMalformedTemporalIntervalsProperty() {
        final List<String> malformedValues = Arrays.asList(
                "", " ", "1000 :2000", "1000: 2000",
                ",1000:2000", "1000:2000,", "1000:2000,,3000:4000",
                ":2000", "1000:", "1000", "1000:2000:3000",
                "one:2000", "1000:two",
                "9223372036854775808:9223372036854775808",
                "-9223372036854775809:0", "2000:1000");

        for (String malformedValue : malformedValues) {
            final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> Lifetime.fromProperties(elementWithTemporalIntervals(malformedValue)));
            assertTrue(exception.getMessage().contains("temporalIntervals"));
        }
    }

    @Test
    public void shouldRejectNonStringTemporalIntervalsProperty() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> Lifetime.fromProperties(elementWithTemporalIntervals(1000L)));

        assertTrue(exception.getMessage().contains("expected String"));
    }

    @Test
    public void shouldRejectEntireTemporalIntervalsPropertyWhenOneComponentIsMalformed() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> Lifetime.fromProperties(elementWithTemporalIntervals("1000:2000,malformed,3000:4000")));

        assertTrue(exception.getMessage().contains("malformed"));
    }

    @Test
    public void shouldDefaultMissingProperties() {
        final Element element = elementWithProperties(null, null);

        final Lifetime lifetime = Lifetime.fromProperties(element);

        assertEquals(Long.MIN_VALUE, lifetime.getStartDate().getTime());
        assertEquals(Long.MAX_VALUE, lifetime.getEndDate().getTime());
        assertEquals(Long.MIN_VALUE, Lifetime.getStartTimeFromProperty(element).getTime());
        assertEquals(Long.MAX_VALUE, Lifetime.getEndTimeFromProperty(element).getTime());
    }

    @Test
    public void shouldWriteDefensiveCopiesToPropertyStructures() {
        final Lifetime lifetime = Lifetime.from(1000L, 2000L);

        final Object[] properties = lifetime.toProperties();
        assertArrayEquals(new Object[] {
                Lifetime.START_TIME, new Date(1000L),
                Lifetime.END_TIME, new Date(2000L),
                Lifetime.TEMPORAL_INTERVALS, "1000:2000"
        }, properties);

        final Date startTimeProperty = (Date) properties[1];
        final Date endTimeProperty = (Date) properties[3];
        startTimeProperty.setTime(3000L);
        endTimeProperty.setTime(4000L);
        assertLifetime(1000L, 2000L, lifetime);

        final Map<String, Object> propertyMap = lifetime.toPropertyMap();
        assertEquals(new Date(1000L), propertyMap.get(Lifetime.START_TIME));
        assertEquals(new Date(2000L), propertyMap.get(Lifetime.END_TIME));
        assertEquals("1000:2000", propertyMap.get(Lifetime.TEMPORAL_INTERVALS));

        ((Date) propertyMap.get(Lifetime.START_TIME)).setTime(3000L);
        ((Date) propertyMap.get(Lifetime.END_TIME)).setTime(4000L);
        assertLifetime(1000L, 2000L, lifetime);
    }

    @Test
    public void shouldAttachDefensiveCopiesToElement() {
        final Lifetime lifetime = Lifetime.from(1000L, 2000L);
        final Element element = mock(Element.class);
        final ArgumentCaptor<Date> startTimeCaptor = ArgumentCaptor.forClass(Date.class);
        final ArgumentCaptor<Date> endTimeCaptor = ArgumentCaptor.forClass(Date.class);

        lifetime.attachTo(element);

        verify(element).property(eq(Lifetime.START_TIME), startTimeCaptor.capture());
        verify(element).property(eq(Lifetime.END_TIME), endTimeCaptor.capture());
        verify(element).property(Lifetime.TEMPORAL_INTERVALS, "1000:2000");
        assertEquals(new Date(1000L), startTimeCaptor.getValue());
        assertEquals(new Date(2000L), endTimeCaptor.getValue());

        startTimeCaptor.getValue().setTime(3000L);
        endTimeCaptor.getValue().setTime(4000L);

        assertLifetime(1000L, 2000L, lifetime);
    }

    @Test
    public void shouldClearEmptyLifetimeFromElementWithMissingProperties() {
        final Element element = mock(Element.class);

        Lifetime.from(Arrays.asList()).attachTo(element);

        verify(element).property(Lifetime.START_TIME);
        verify(element).property(Lifetime.END_TIME);
        verify(element).property(Lifetime.TEMPORAL_INTERVALS);
    }

    @Test
    public void shouldSerializeAndDeserializeLifetime() throws Exception {
        final Lifetime lifetime = Lifetime.from(1000L, 2000L);

        assertEquals(lifetime, Serializer.deserializeObject(Serializer.serializeObject(lifetime)));
    }

    @Test
    public void shouldSerializeTraversalContainingLifetimeStep() throws Exception {
        final Traversal<?, ?> traversal = __.lifetime("1970-01-01T00:00:01Z", "1970-01-01T00:00:02Z");

        assertEquals(traversal, Serializer.deserializeObject(Serializer.serializeObject(traversal)));
    }

    @Test
    public void shouldSupportMultipleIntervals() {
        Lifetime lifetime = Lifetime.from(1000L, 2000L);
        lifetime = lifetime.addInterval(new Date(3000L), new Date(4000L));
        
        assertLifetime(1000L, 4000L, lifetime);
        assertTrue(lifetime.intersects(Lifetime.from(1500L, 1800L)));
        assertTrue(lifetime.intersects(Lifetime.from(3500L, 3800L)));
        assertFalse(lifetime.intersects(Lifetime.from(2100L, 2900L)));
    }

    @Test
    public void shouldDropIntervals() {
        Lifetime lifetime = Lifetime.from(1000L, 4000L);
        lifetime = lifetime.dropInterval(new Date(2000L), new Date(3000L));
        
        assertLifetime(1000L, 4000L, lifetime);
        assertEquals(Arrays.asList(
                new Lifetime.Interval(new Date(1000L), new Date(2000L)),
                new Lifetime.Interval(new Date(3000L), new Date(4000L))),
                lifetime.getIntervals());
        assertFalse(lifetime.intersects(Lifetime.from(2100L, 2900L)));
        assertTrue(lifetime.intersects(Lifetime.from(1500L, 1800L)));
        assertTrue(lifetime.intersects(Lifetime.from(3500L, 3800L)));
    }

    @Test
    public void shouldRespectHalfOpenIntervalSemantics() {
        Lifetime.Interval interval = new Lifetime.Interval(new Date(100L), new Date(200L));
        // start is inclusive
        assertTrue(interval.contains(new Date(100L)));
        // interior is inclusive
        assertTrue(interval.contains(new Date(150L)));
        // end is exclusive
        assertFalse(interval.contains(new Date(200L)));
        assertFalse(interval.contains(new Date(99L)));
        assertFalse(interval.contains(new Date(201L)));

        // Abutting intervals do not intersect
        Lifetime.Interval abutting = new Lifetime.Interval(new Date(200L), new Date(300L));
        assertFalse(interval.intersects(abutting));
        assertFalse(abutting.intersects(interval));

        // Overlapping intervals intersect
        Lifetime.Interval overlapping = new Lifetime.Interval(new Date(199L), new Date(300L));
        assertTrue(interval.intersects(overlapping));
        assertTrue(overlapping.intersects(interval));

        // Point interval containment
        Lifetime.Interval point = new Lifetime.Interval(new Date(150L), new Date(150L));
        assertTrue(point.contains(new Date(150L)));
        assertFalse(point.contains(new Date(151L)));
        assertTrue(interval.intersects(point));
        assertTrue(point.intersects(interval));

        Lifetime.Interval pointAtBoundary = new Lifetime.Interval(new Date(200L), new Date(200L));
        assertFalse(interval.intersects(pointAtBoundary));
        assertFalse(pointAtBoundary.intersects(interval));
    }

    @Test
    public void shouldHandleUnboundedLifetimeSingletonAndStaticElements() {
        assertSame(Lifetime.UNBOUNDED, Lifetime.from(null, null));
        assertTrue(Lifetime.UNBOUNDED.isUnbounded());

        final Element staticElement = mock(Element.class);
        when(staticElement.property(Lifetime.START_TIME)).thenReturn(Property.empty());
        when(staticElement.property(Lifetime.END_TIME)).thenReturn(Property.empty());
        when(staticElement.property(Lifetime.TEMPORAL_INTERVALS)).thenReturn(Property.empty());

        assertFalse(Lifetime.hasLifetimeProperties(staticElement));
        assertSame(Lifetime.UNBOUNDED, Lifetime.fromProperties(staticElement));

        // Unbounded properties should be empty
        assertEquals(0, Lifetime.UNBOUNDED.toProperties().length);
        assertTrue(Lifetime.UNBOUNDED.toPropertyMap().isEmpty());

        // Fast path operations with unbounded
        final Lifetime finite = Lifetime.from(100L, 200L);
        assertTrue(Lifetime.UNBOUNDED.intersects(finite));
        assertTrue(finite.intersects(Lifetime.UNBOUNDED));
        assertTrue(Lifetime.UNBOUNDED.contains(finite));
        assertEquals(finite, Lifetime.UNBOUNDED.intersection(finite));
        assertEquals(finite, finite.intersection(Lifetime.UNBOUNDED));
    }

    @Test
    public void shouldPreservePointIntervalAbuttingHalfOpenEndDuringNormalization() {
        // [100, 200) is half-open (200 excluded); adding point interval [200, 200] must keep 200 active
        Lifetime lifetime = Lifetime.from(100L, 200L).addInterval(new Date(200L), new Date(200L));
        assertTrue("Instant 200 must remain contained after adding point interval [200, 200] to [100, 200)",
                lifetime.contains(new Date(200L)));
        assertTrue(lifetime.contains(new Date(100L)));
        assertTrue(lifetime.contains(new Date(150L)));
        assertFalse(lifetime.contains(new Date(201L)));

        // Adding [200, 300) afterwards should merge all three into a single [100, 300) interval
        lifetime = lifetime.addInterval(new Date(200L), new Date(300L));
        assertEquals(1, lifetime.getIntervals().size());
        assertEquals(new Lifetime.Interval(new Date(100L), new Date(300L)), lifetime.getIntervals().get(0));
    }

    @Test
    public void shouldRoundTripGraphBinaryForTemporalTypes() throws Exception {
        final org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryWriter writer =
                new org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryWriter();
        final org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryReader reader =
                new org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryReader();

        final Lifetime multi = Lifetime.from(1000L, 2000L).addInterval(new Date(3000L), new Date(4000L));
        for (final Object obj : Arrays.asList(
                multi,
                Lifetime.UNBOUNDED,
                Lifetime.EMPTY,
                org.apache.tinkerpop.gremlin.process.traversal.TemporalP.intersects(multi),
                org.apache.tinkerpop.gremlin.process.traversal.step.map.LifetimeStep.LifetimeMode.ADD,
                Lifetime.DropMode.HARD,
                org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget.EARLIEST_ARRIVAL,
                __.window(multi).dropInterval(1000L, 2000L, Lifetime.DropMode.HARD).temporalIntersects(multi).asAdmin().getBytecode()
        )) {
            final org.apache.tinkerpop.gremlin.structure.io.Buffer buffer = createTestBuffer(4096);
            writer.write(obj, buffer);
            final Object read = reader.read(buffer);
            assertEquals(obj, read);
            if (obj == Lifetime.UNBOUNDED || obj == Lifetime.EMPTY) {
                assertSame(obj, read);
            }
        }
    }

    @Test
    public void shouldRoundTripGraphSONV2AndV3ForTemporalTypes() throws Exception {
        final Lifetime multi = Lifetime.from(1000L, 2000L).addInterval(new Date(3000L), new Date(4000L));
        for (final org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONVersion version : Arrays.asList(
                org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONVersion.V2_0,
                org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONVersion.V3_0)) {
            final org.apache.tinkerpop.shaded.jackson.databind.ObjectMapper mapper =
                    org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONMapper.build()
                            .version(version)
                            .typeInfo(org.apache.tinkerpop.gremlin.structure.io.graphson.TypeInfo.PARTIAL_TYPES)
                            .create()
                            .createMapper();

            for (final Object obj : Arrays.asList(
                    multi,
                    Lifetime.UNBOUNDED,
                    Lifetime.EMPTY,
                    org.apache.tinkerpop.gremlin.process.traversal.TemporalP.intersects(multi),
                    org.apache.tinkerpop.gremlin.process.traversal.step.map.LifetimeStep.LifetimeMode.DROP,
                    Lifetime.DropMode.HARD,
                    org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget.LATEST_DEPARTURE,
                    __.window(multi).dropInterval(1000L, 2000L, Lifetime.DropMode.HARD).temporalIntersects(multi).asAdmin().getBytecode()
            )) {
                final String json = mapper.writeValueAsString(obj);
                final Object read = mapper.readValue(json, Object.class);
                assertEquals(obj, read);
                if (obj == Lifetime.UNBOUNDED || obj == Lifetime.EMPTY) {
                    assertSame(obj, read);
                }
            }
        }
    }

    @Test
    public void shouldRoundTripGryoV1AndV3ForTemporalTypes() throws Exception {
        final Lifetime multi = Lifetime.from(1000L, 2000L).addInterval(new Date(3000L), new Date(4000L));
        for (final org.apache.tinkerpop.gremlin.structure.io.gryo.GryoVersion version : Arrays.asList(
                org.apache.tinkerpop.gremlin.structure.io.gryo.GryoVersion.V1_0,
                org.apache.tinkerpop.gremlin.structure.io.gryo.GryoVersion.V3_0)) {
            final org.apache.tinkerpop.gremlin.structure.io.gryo.GryoMapper mapper =
                    org.apache.tinkerpop.gremlin.structure.io.gryo.GryoMapper.build().version(version).create();
            final org.apache.tinkerpop.gremlin.structure.io.gryo.GryoWriter writer =
                    org.apache.tinkerpop.gremlin.structure.io.gryo.GryoWriter.build().mapper(mapper).create();
            final org.apache.tinkerpop.gremlin.structure.io.gryo.GryoReader reader =
                    org.apache.tinkerpop.gremlin.structure.io.gryo.GryoReader.build().mapper(mapper).create();

            for (final Object obj : Arrays.asList(
                    multi,
                    Lifetime.UNBOUNDED,
                    Lifetime.EMPTY,
                    org.apache.tinkerpop.gremlin.process.traversal.TemporalP.intersects(multi),
                    org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.TemporalGraphViewStrategy.instance(),
                    org.apache.tinkerpop.gremlin.process.traversal.step.map.LifetimeStep.LifetimeMode.ADD,
                    Lifetime.DropMode.HARD,
                    org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget.EARLIEST_DEPARTURE,
                    __.window(multi).dropInterval(1000L, 2000L, Lifetime.DropMode.HARD).temporalIntersects(multi).asAdmin().getBytecode()
            )) {
                final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                writer.writeObject(out, obj);
                final Object read = reader.readObject(new java.io.ByteArrayInputStream(out.toByteArray()), Object.class);
                assertEquals(obj, read);
                if (obj == Lifetime.UNBOUNDED || obj == Lifetime.EMPTY) {
                    assertSame(obj, read);
                }
            }
        }
    }

    private static org.apache.tinkerpop.gremlin.structure.io.Buffer createTestBuffer(final int capacity) {
        final java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(capacity);
        return new org.apache.tinkerpop.gremlin.structure.io.Buffer() {
            private int rIdx = 0;
            private int wIdx = 0;
            private int markWIdx = 0;
            @Override public int readableBytes() { return wIdx - rIdx; }
            @Override public int readerIndex() { return rIdx; }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer readerIndex(final int idx) { rIdx = idx; return this; }
            @Override public int writerIndex() { return wIdx; }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer writerIndex(final int idx) { wIdx = idx; return this; }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer markWriterIndex() { markWIdx = wIdx; return this; }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer resetWriterIndex() { wIdx = markWIdx; return this; }
            @Override public int capacity() { return bb.capacity(); }
            @Override public boolean isDirect() { return false; }
            @Override public boolean readBoolean() { return readByte() != 0; }
            @Override public byte readByte() { return bb.get(rIdx++); }
            @Override public short readShort() { final short v = bb.getShort(rIdx); rIdx += 2; return v; }
            @Override public int readInt() { final int v = bb.getInt(rIdx); rIdx += 4; return v; }
            @Override public long readLong() { final long v = bb.getLong(rIdx); rIdx += 8; return v; }
            @Override public float readFloat() { final float v = bb.getFloat(rIdx); rIdx += 4; return v; }
            @Override public double readDouble() { final double v = bb.getDouble(rIdx); rIdx += 8; return v; }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer readBytes(final byte[] dst) { return readBytes(dst, 0, dst.length); }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer readBytes(final byte[] dst, final int dstIdx, final int len) {
                for (int i = 0; i < len; i++) dst[dstIdx + i] = bb.get(rIdx++);
                return this;
            }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer readBytes(final java.nio.ByteBuffer dst) {
                while (dst.hasRemaining()) dst.put(bb.get(rIdx++));
                return this;
            }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer readBytes(final java.io.OutputStream out, final int len) throws java.io.IOException {
                final byte[] tmp = new byte[len]; readBytes(tmp); out.write(tmp); return this;
            }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer writeBoolean(final boolean v) { return writeByte(v ? 1 : 0); }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer writeByte(final int v) { bb.put(wIdx++, (byte) v); return this; }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer writeShort(final int v) { bb.putShort(wIdx, (short) v); wIdx += 2; return this; }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer writeInt(final int v) { bb.putInt(wIdx, v); wIdx += 4; return this; }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer writeLong(final long v) { bb.putLong(wIdx, v); wIdx += 8; return this; }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer writeFloat(final float v) { bb.putFloat(wIdx, v); wIdx += 4; return this; }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer writeDouble(final double v) { bb.putDouble(wIdx, v); wIdx += 8; return this; }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer writeBytes(final byte[] src) { return writeBytes(src, 0, src.length); }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer writeBytes(final java.nio.ByteBuffer src) {
                while (src.hasRemaining()) bb.put(wIdx++, src.get());
                return this;
            }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer writeBytes(final byte[] src, final int srcIdx, final int len) {
                for (int i = 0; i < len; i++) bb.put(wIdx++, src[srcIdx + i]);
                return this;
            }
            @Override public boolean release() { return true; }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer retain() { return this; }
            @Override public int referenceCount() { return 1; }
            @Override public int nioBufferCount() { return 1; }
            @Override public java.nio.ByteBuffer[] nioBuffers() { return new java.nio.ByteBuffer[]{nioBuffer()}; }
            @Override public java.nio.ByteBuffer[] nioBuffers(final int idx, final int len) { return new java.nio.ByteBuffer[]{nioBuffer(idx, len)}; }
            @Override public java.nio.ByteBuffer nioBuffer() { return nioBuffer(rIdx, readableBytes()); }
            @Override public java.nio.ByteBuffer nioBuffer(final int idx, final int len) {
                final java.nio.ByteBuffer dup = bb.duplicate(); dup.position(idx); dup.limit(idx + len); return dup.slice();
            }
            @Override public org.apache.tinkerpop.gremlin.structure.io.Buffer getBytes(final int idx, final byte[] dst) {
                for (int i = 0; i < dst.length; i++) dst[i] = bb.get(idx + i);
                return this;
            }
        };
    }

    @Test
    public void shouldTreatStarAdjacentVertexAsUnboundedWithoutThrowing() {
        final org.apache.tinkerpop.gremlin.structure.util.star.StarGraph starGraph =
                org.apache.tinkerpop.gremlin.structure.util.star.StarGraph.open();
        final org.apache.tinkerpop.gremlin.structure.Vertex v1 = starGraph.addVertex("person");
        final org.apache.tinkerpop.gremlin.structure.Vertex v2 = starGraph.addVertex("person");
        final org.apache.tinkerpop.gremlin.structure.Edge e = v1.addEdge("knows", v2);
        final org.apache.tinkerpop.gremlin.structure.Vertex adjacent = e.inVertex();

        assertFalse(Lifetime.hasLifetimeProperties(adjacent));
        assertSame(Lifetime.UNBOUNDED, Lifetime.fromProperties(adjacent));
    }

    private static Element elementWithProperties(final Object startTime, final Object endTime) {
        final Element element = mock(Element.class);
        final Property<Object> startTimeProperty = null == startTime ? Property.empty() : property(startTime);
        final Property<Object> endTimeProperty = null == endTime ? Property.empty() : property(endTime);
        when(element.property(Lifetime.START_TIME)).thenReturn(startTimeProperty);
        when(element.property(Lifetime.END_TIME)).thenReturn(endTimeProperty);
        when(element.property(Lifetime.TEMPORAL_INTERVALS)).thenReturn(Property.empty());
        return element;
    }

    private static Element elementWithTemporalIntervals(final Object temporalIntervals) {
        final Element element = mock(Element.class);
        final Property<Object> temporalIntervalsProperty = property(temporalIntervals);
        when(element.property(Lifetime.TEMPORAL_INTERVALS)).thenReturn(temporalIntervalsProperty);
        return element;
    }

    private static <V> Property<V> property(final V value) {
        final Property<V> property = mock(Property.class);
        when(property.isPresent()).thenReturn(true);
        when(property.value()).thenReturn(value);
        when(property.orElse(null)).thenReturn(value);
        return property;
    }

    private static void assertLifetime(final long startTime, final long endTime, final Lifetime lifetime) {
        assertEquals(new Date(startTime), lifetime.getStartDate());
        assertEquals(new Date(endTime), lifetime.getEndDate());
    }
}
