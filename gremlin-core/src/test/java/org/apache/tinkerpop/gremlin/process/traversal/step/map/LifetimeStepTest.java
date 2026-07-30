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
package org.apache.tinkerpop.gremlin.process.traversal.step.map;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.StepTest;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.util.DatetimeHelper;
import org.junit.Test;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class LifetimeStepTest extends StepTest {

    private final List<Traversal> traversals = Arrays.asList(
            __.lifetime("2023-01-01", "2023-12-31"),
            __.lifetime("2023-01-01"),
            __.lifetime("2023-01-01T10:30:00", "2023-12-31T23:59:59")
    );

    @Override
    protected List<Traversal> getTraversals() {
        return traversals;
    }

    @Test
    public void shouldAcceptValidDateFormats() {
        assertLifetime("2023-01-01", "2023-12-31");
        assertLifetime("2023-01-01T10:30:00", "2023-12-31T23:59:59");
        assertLifetime("2023-01-01T10:30:00Z", "2023-12-31T23:59:59Z");
    }

    @Test
    public void shouldAcceptNullStartTime() {
        final LifetimeStep<Vertex> step = lifetimeStep(null, "2023-12-31");

        assertEquals(Lifetime.toStartDate(Lifetime.MIN_START_TIME), step.getLifetime().getStartDate());
        assertEquals(DatetimeHelper.parse("2023-12-31"), step.getLifetime().getEndDate());
    }

    @Test
    public void shouldAcceptNullEndTime() {
        final LifetimeStep<Vertex> step = lifetimeStep("2023-01-01", null);

        assertEquals(DatetimeHelper.parse("2023-01-01"), step.getLifetime().getStartDate());
        assertEquals(Lifetime.toEndDate(Lifetime.MAX_END_TIME), step.getLifetime().getEndDate());
    }

    @Test
    public void shouldAcceptOpenLifetime() {
        final LifetimeStep<Vertex> step = lifetimeStep(null, null);

        assertEquals(Lifetime.toStartDate(Lifetime.MIN_START_TIME), step.getLifetime().getStartDate());
        assertEquals(Lifetime.toEndDate(Lifetime.MAX_END_TIME), step.getLifetime().getEndDate());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectEmptyStartTime() {
        lifetimeStep("", "2023-12-31");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectWhitespaceStartTime() {
        lifetimeStep("   ", "2023-12-31");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectEmptyEndTime() {
        lifetimeStep("2023-01-01", "");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectWhitespaceEndTime() {
        lifetimeStep("2023-01-01", "   ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidStartTimeFormat() {
        lifetimeStep("invalid-date", "2023-12-31");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidEndTimeFormat() {
        lifetimeStep("2023-01-01", "invalid-date");
    }

    @Test
    public void shouldAcceptEqualStartAndEndTime() {
        final LifetimeStep<Vertex> step = lifetimeStep("2023-01-01", "2023-01-01");

        assertEquals(DatetimeHelper.parse("2023-01-01"), step.getLifetime().getStartDate());
        assertEquals(DatetimeHelper.parse("2023-01-01"), step.getLifetime().getEndDate());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectStartTimeAfterEndTime() {
        lifetimeStep("2023-12-31", "2023-01-01");
    }

    @Test
    public void shouldHandleDifferentDateFormats() {
        final String[] validStartDates = {
                "2023-01-01",
                "2023-01-01T00:00:00",
                "2023-01-01T00:00:00Z",
                "2023-01-01T00:00:00.000Z"
        };

        final String[] validEndDates = {
                "2023-12-31",
                "2023-12-31T23:59:59",
                "2023-12-31T23:59:59Z",
                "2023-12-31T23:59:59.999Z"
        };

        for (String startDate : validStartDates) {
            for (String endDate : validEndDates) {
                try {
                    assertLifetime(startDate, endDate);
                } catch (IllegalArgumentException e) {
                    fail("Should accept valid date format: " + startDate + " -> " + endDate + ", but got: " + e.getMessage());
                }
            }
        }
    }

    @Test
    public void shouldRejectInvalidDateFormats() {
        final String[] invalidDates = {
                "not-a-date",
                "2023-13-01",
                "2023-01-32",
                "2023/13/01",
                "2023/01/32",
                "32/01/2023",
                "13/13/2023",
                "2023-01-01T25:00:00",
                "2023-01-01T00:60:00",
                "2023-01-01T00:00:60"
        };

        for (String invalidDate : invalidDates) {
            try {
                lifetimeStep(invalidDate, "2023-12-31");
                fail("Should reject invalid date format: " + invalidDate);
            } catch (IllegalArgumentException e) {
                assertNotNull(e.getMessage());
            }

            try {
                lifetimeStep("2023-01-01", invalidDate);
                fail("Should reject invalid date format: " + invalidDate);
            } catch (IllegalArgumentException e) {
                assertNotNull(e.getMessage());
            }
        }
    }

    @Test
    public void shouldTestChainedLifetimeSteps() {
        final LifetimeStep<Vertex> step1 = lifetimeStep("2004-02-02", "2004-03-03");
        final LifetimeStep<Vertex> step2 = lifetimeStep("2004-02-02", "2024-03-03");

        assertLifetime(step1, "2004-02-02", "2004-03-03");
        assertLifetime(step2, "2004-02-02", "2024-03-03");
        assertNotEquals(step1, step2);
    }

    @Test
    public void shouldTestEndTimeChange() {
        final LifetimeStep<Vertex> step1 = lifetimeStep("2020-01-01", "2021-01-01");
        final LifetimeStep<Vertex> step2 = lifetimeStep("2020-01-01", "2030-01-01");
        final LifetimeStep<Vertex> step3 = lifetimeStep("2020-01-01", "2025-01-01");

        assertLifetime(step1, "2020-01-01", "2021-01-01");
        assertLifetime(step2, "2020-01-01", "2030-01-01");
        assertLifetime(step3, "2020-01-01", "2025-01-01");

        assertNotEquals(step1, step2);
        assertNotEquals(step2, step3);
        assertNotEquals(step1, step3);
    }

    @Test
    public void shouldTestLifetimeWithPropertyKey() {
        final LifetimeStep<Vertex> step1 = lifetimeStep("2004-02-02", "2004-03-03", "name", "john");
        final LifetimeStep<Vertex> step2 = lifetimeStep("2004-02-02", "2024-03-03", "name", null);

        assertLifetime(step1, "2004-02-02", "2004-03-03");
        assertLifetime(step2, "2004-02-02", "2024-03-03");
        assertNotEquals(step1, step2);
    }

    @Test
    public void shouldTestLifetimeOnEdge() {
        final LifetimeStep<Edge> step1 = new LifetimeStep<>(__.start().asAdmin(), Lifetime.from("2004-02-02", "2004-03-03"), null, null);
        final LifetimeStep<Edge> step2 = new LifetimeStep<>(__.start().asAdmin(), Lifetime.from("2004-02-02", "2024-03-03"), null, null);

        assertLifetime(step1, "2004-02-02", "2004-03-03");
        assertLifetime(step2, "2004-02-02", "2024-03-03");
        assertNotEquals(step1, step2);
    }

    @Test
    public void shouldTestLifetimeWithDefaultEndTime() {
        final LifetimeStep<Vertex> step1 = lifetimeStep("2004-02-02", null);
        final LifetimeStep<Vertex> step2 = lifetimeStep("2004-02-02", Lifetime.MAX_END_TIME);
        final LifetimeStep<Vertex> step3 = lifetimeStep("2004-02-02", "2024-03-03");

        assertEquals(DatetimeHelper.parse("2004-02-02"), step1.getLifetime().getStartDate());
        assertEquals(Lifetime.toEndDate(Lifetime.MAX_END_TIME), step1.getLifetime().getEndDate());
        assertEquals(DatetimeHelper.parse("2004-02-02"), step2.getLifetime().getStartDate());
        assertEquals(Lifetime.toEndDate(Lifetime.MAX_END_TIME), step2.getLifetime().getEndDate());
        assertLifetime(step3, "2004-02-02", "2024-03-03");

        assertNotEquals(step1, step3);
        assertNotEquals(step2, step3);
    }

    @Test
    public void shouldTestLifetimeWithSupportedDateFormats() {
        final LifetimeStep<Vertex> step1 = lifetimeStep("2004-02-02", "2004-03-03");
        final LifetimeStep<Vertex> step2 = lifetimeStep("2004-02-02T00:00:00", "2004-03-03T00:00:00");

        assertLifetime(step1, "2004-02-02", "2004-03-03");
        assertLifetime(step2, "2004-02-02T00:00:00", "2004-03-03T00:00:00");
        assertEquals(step1, step2);
    }

    @Test
    public void shouldTestLifetimeStepEquality() {
        final Lifetime lifetime = Lifetime.from("2004-02-02", "2004-03-03");
        final LifetimeStep<Vertex> step1 = new LifetimeStep<>(__.start().asAdmin(), lifetime, null, null);
        final LifetimeStep<Vertex> step2 = new LifetimeStep<>(__.start().asAdmin(), lifetime, null, null);
        final LifetimeStep<Vertex> step3 = lifetimeStep("2004-02-02", "2024-03-03");

        assertEquals(step1, step2);
        assertNotEquals(step1, step3);

        final LifetimeStep<Vertex> step4 = new LifetimeStep<>(__.start().asAdmin(), lifetime, "name", "john");
        final LifetimeStep<Vertex> step5 = new LifetimeStep<>(__.start().asAdmin(), lifetime, "name", "john");
        final LifetimeStep<Vertex> step6 = new LifetimeStep<>(__.start().asAdmin(), lifetime, "name", "jane");

        assertEquals(step4, step5);
        assertNotEquals(step4, step6);
    }

    @Test
    public void shouldTestLifetimeStepHashCode() {
        final Lifetime lifetime = Lifetime.from("2004-02-02", "2004-03-03");
        final LifetimeStep<Vertex> step1 = new LifetimeStep<>(__.start().asAdmin(), lifetime, null, null);
        final LifetimeStep<Vertex> step2 = new LifetimeStep<>(__.start().asAdmin(), lifetime, null, null);
        final LifetimeStep<Vertex> step3 = lifetimeStep("2004-02-02", "2024-03-03");

        assertEquals(step1.hashCode(), step2.hashCode());
        assertNotEquals(step1.hashCode(), step3.hashCode());
    }

    @Test
    public void shouldTestLifetimeWithExistingStartTimeAndNewEndTime() {
        final LifetimeStep<Vertex> step1 = lifetimeStep("2004-02-02", null);
        assertEquals(DatetimeHelper.parse("2004-02-02"), step1.getLifetime().getStartDate());
        assertEquals(Lifetime.toEndDate(Lifetime.MAX_END_TIME), step1.getLifetime().getEndDate());

        final LifetimeStep<Vertex> step2 = lifetimeStep("2020-01-01", null);
        assertEquals(DatetimeHelper.parse("2020-01-01"), step2.getLifetime().getStartDate());
        assertEquals(Lifetime.toEndDate(Lifetime.MAX_END_TIME), step2.getLifetime().getEndDate());

        final LifetimeStep<Vertex> step3 = lifetimeStep("2004-02-02", null, "name", "john");
        assertEquals(DatetimeHelper.parse("2004-02-02"), step3.getLifetime().getStartDate());
        assertEquals(Lifetime.toEndDate(Lifetime.MAX_END_TIME), step3.getLifetime().getEndDate());

        final LifetimeStep<Vertex> step4 = lifetimeStep("2004-02-02", "2024-03-03");
        assertLifetime(step4, "2004-02-02", "2024-03-03");

        final LifetimeStep<Vertex> step5 = lifetimeStep("2004-02-02", Lifetime.MAX_END_TIME);
        assertEquals(DatetimeHelper.parse("2004-02-02"), step5.getLifetime().getStartDate());
        assertEquals(Lifetime.toEndDate(Lifetime.MAX_END_TIME), step5.getLifetime().getEndDate());
    }

    private static LifetimeStep<Vertex> lifetimeStep(final Object startTime, final Object endTime) {
        return lifetimeStep(startTime, endTime, null, null);
    }

    private static LifetimeStep<Vertex> lifetimeStep(final Object startTime, final Object endTime,
                                                     final String propertyKey, final String propertyValue) {
        return new LifetimeStep<>(__.start().asAdmin(), Lifetime.from(startTime, endTime), propertyKey, propertyValue);
    }

    private static void assertLifetime(final Object startTime, final Object endTime) {
        assertLifetime(lifetimeStep(startTime, endTime), startTime, endTime);
    }

    private static void assertLifetime(final LifetimeStep<?> step, final Object startTime, final Object endTime) {
        assertEquals(Lifetime.toStartDate(startTime), step.getLifetime().getStartDate());
        assertEquals(Lifetime.toEndDate(endTime), step.getLifetime().getEndDate());
    }
}
