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
        assertEquals(new Date(1000L), startTimeCaptor.getValue());
        assertEquals(new Date(2000L), endTimeCaptor.getValue());

        startTimeCaptor.getValue().setTime(3000L);
        endTimeCaptor.getValue().setTime(4000L);

        assertLifetime(1000L, 2000L, lifetime);
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
        assertFalse(lifetime.intersects(Lifetime.from(2100L, 2900L)));
        assertTrue(lifetime.intersects(Lifetime.from(1500L, 1800L)));
        assertTrue(lifetime.intersects(Lifetime.from(3500L, 3800L)));
    }

    private static Element elementWithProperties(final Object startTime, final Object endTime) {
        final Element element = mock(Element.class);
        final Property<Object> startTimeProperty = null == startTime ? Property.empty() : property(startTime);
        final Property<Object> endTimeProperty = null == endTime ? Property.empty() : property(endTime);
        when(element.property(Lifetime.START_TIME)).thenReturn(startTimeProperty);
        when(element.property(Lifetime.END_TIME)).thenReturn(endTimeProperty);
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
