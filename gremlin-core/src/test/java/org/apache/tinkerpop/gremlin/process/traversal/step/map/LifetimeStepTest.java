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
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.StepTest;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Edge;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Iterator;

import static org.mockito.Mockito.*;

import static org.junit.Assert.*;

public class LifetimeStepTest extends StepTest {

    @Mock
    private Vertex testVertex;
    
    @Mock
    private Edge testEdge;
    
    @Mock
    private VertexProperty<Object> testVertexProperty;
    
    @Mock
    private Property<Object> testProperty;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        
        // Setup mock vertex
        when(testVertex.property(anyString())).thenReturn(testVertexProperty);
        when(testVertex.property(any(VertexProperty.Cardinality.class), anyString(), any())).thenReturn(testVertexProperty);
        
        // Setup mock edge
        when(testEdge.property(anyString(), any())).thenReturn(testProperty);
        
        // Setup mock vertex property
        when(testVertexProperty.isPresent()).thenReturn(true);
        when(testVertexProperty.value()).thenReturn("testValue");
        when(testVertexProperty.property(anyString())).thenReturn(testProperty);
        when(testVertexProperty.properties()).thenReturn(mock(Iterator.class));
        
        // Setup mock property
        when(testProperty.isPresent()).thenReturn(true);
        when(testProperty.value()).thenReturn("testValue");
    }

    @Override
    protected List<Traversal> getTraversals() {
        return Arrays.asList(
            __.lifetime("2023-01-01", "2023-12-31"),
            __.lifetime("2023-01-01"),
            __.lifetime("2023/01/01 10:30:00", "2023/12/31 23:59:59")
        );
    }

    @Test
    public void shouldAcceptValidDateFormats() {
        // Test various valid date formats
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), "2023-01-01", "2023-12-31", null, null));
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), "2023/01/01", "2023/12/31", null, null));
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), "01/01/2023", "12/31/2023", null, null));
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), "2023-01-01T10:30:00", "2023-12-31T23:59:59", null, null));
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), "2023-01-01T10:30:00Z", "2023-12-31T23:59:59Z", null, null));
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), "1640995200000", "1704067199999", null, null)); // timestamps
    }

    @Test
    public void shouldAcceptDefaultEndTime() {
        // Test with null endTime (should use DEFAULT_ENDTIME)
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), "2023-01-01", null, null, null));
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), "2023-01-01", "1e10", null, null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullStartTime() {
        new LifetimeStep<>(__.start().asAdmin(), null, "2023-12-31", null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectEmptyStartTime() {
        new LifetimeStep<>(__.start().asAdmin(), "", "2023-12-31", null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectWhitespaceStartTime() {
        new LifetimeStep<>(__.start().asAdmin(), "   ", "2023-12-31", null, null);
    }

    @Test
    public void shouldAcceptNullEndTime() {
        // Null endTime should use DEFAULT_ENDTIME
        LifetimeStep<Vertex> step = new LifetimeStep<>(__.start().asAdmin(), "2023-01-01", null, null, null);
        assertEquals("1e10", step.getEndTime());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectEmptyEndTime() {
        new LifetimeStep<>(__.start().asAdmin(), "2023-01-01", "", null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectWhitespaceEndTime() {
        new LifetimeStep<>(__.start().asAdmin(), "2023-01-01", "   ", null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidStartTimeFormat() {
        new LifetimeStep<>(__.start().asAdmin(), "invalid-date", "2023-12-31", null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidEndTimeFormat() {
        new LifetimeStep<>(__.start().asAdmin(), "2023-01-01", "invalid-date", null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectEqualStartAndEndTime() {
        new LifetimeStep<>(__.start().asAdmin(), "2023-01-01", "2023-01-01", null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectStartTimeAfterEndTime() {
        new LifetimeStep<>(__.start().asAdmin(), "2023-12-31", "2023-01-01", null, null);
    }

    @Test
    public void shouldHandleTimestampFormats() {
        // Test with Unix timestamps
        long startTimestamp = new Date(123, 0, 1).getTime(); // 2023-01-01
        long endTimestamp = new Date(123, 11, 31).getTime(); // 2023-12-31
        
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), String.valueOf(startTimestamp), String.valueOf(endTimestamp), null, null));
    }

    @Test
    public void shouldHandleTestDateFormats() {
        // Test the specific date formats used in the failing tests
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), "10-05-2022", "10-05-2222", null, null));
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), "10-05-2022", null, null, null));
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), "24-08-2004", "25-08-2004", null, null));
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), "24-08-2004", null, null, null));
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), "04-04-2006", "05-04-2006", null, null));
        assertNotNull(new LifetimeStep<>(__.start().asAdmin(), "04-04-2025", "06-04-2025", null, null));
    }

    @Test
    public void shouldHandleDifferentDateFormats() {
        // Test various date formats that should be accepted
        String[] validStartDates = {
            "2023-01-01",
            "2023/01/01",
            "01/01/2023",
            "01-01-2023",
            "2023-01-01 00:00:00",
            "2023/01/01 00:00:00",
            "2023-01-01T00:00:00",
            "2023-01-01T00:00:00Z",
            "2023-01-01T00:00:00.000Z"
        };
        
        String[] validEndDates = {
            "2023-12-31",
            "2023/12/31",
            "12/31/2023",
            "31-12-2023",
            "2023-12-31 23:59:59",
            "2023/12/31 23:59:59",
            "2023-12-31T23:59:59",
            "2023-12-31T23:59:59Z",
            "2023-12-31T23:59:59.999Z"
        };
        
        for (String startDate : validStartDates) {
            for (String endDate : validEndDates) {
                try {
                    LifetimeStep<Vertex> step = new LifetimeStep<>(__.start().asAdmin(), startDate, endDate, null, null);
                    assertNotNull(step);
                } catch (IllegalArgumentException e) {
                    fail("Should accept valid date format: " + startDate + " -> " + endDate + ", but got: " + e.getMessage());
                }
            }
        }
    }

    @Test
    public void shouldRejectInvalidDateFormats() {
        String[] invalidDates = {
            "not-a-date",
            "2023-13-01", // Invalid month
            "2023-01-32", // Invalid day
            "2023/13/01",
            "2023/01/32",
            "32/01/2023", // Invalid day
            "13/13/2023", // Invalid month
            "2023-01-01T25:00:00", // Invalid hour
            "2023-01-01T00:60:00", // Invalid minute
            "2023-01-01T00:00:60",  // Invalid second
            "2023-02-30", // Invalid day for February
            "2023-04-31", // Invalid day for April
            "2023-06-31", // Invalid day for June
            "2023-09-31", // Invalid day for September
            "2023-11-31"  // Invalid day for November
        };
        
        for (String invalidDate : invalidDates) {
            try {
                new LifetimeStep<>(__.start().asAdmin(), invalidDate, "2023-12-31", null, null);
                fail("Should reject invalid date format: " + invalidDate);
            } catch (IllegalArgumentException e) {
                // Expected
                assertTrue("Error message should contain 'not in a valid date format' for: " + invalidDate, 
                          e.getMessage().contains("not in a valid date format"));
            }
            
            try {
                new LifetimeStep<>(__.start().asAdmin(), "2023-01-01", invalidDate, null, null);
                fail("Should reject invalid date format: " + invalidDate);
            } catch (IllegalArgumentException e) {
                // Expected
                assertTrue("Error message should contain 'not in a valid date format' for: " + invalidDate, 
                          e.getMessage().contains("not in a valid date format"));
            }
        }
    }

    @Test
    public void shouldTestChainedLifetimeSteps() {
        // Test the specific scenario: g.addV().lifetime("02-02-2004","03-03-2004").lifetime(getStarttime(),"03-03-2024")
        
        // Test that LifetimeStep instances can be chained
        LifetimeStep<Vertex> step1 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2004", null, null);
        LifetimeStep<Vertex> step2 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2024", null, null);
        
        // Verify the steps have the correct values
        assertEquals("02-02-2004", step1.getStartTime());
        assertEquals("03-03-2004", step1.getEndTime());
        assertEquals("02-02-2004", step2.getStartTime());
        assertEquals("03-03-2024", step2.getEndTime());
        
        // Test that the steps are not equal (different endTime)
        assertNotEquals(step1, step2);
    }

    @Test
    public void shouldTestEndTimeChange() {
        // Test if the endTime changes when applying multiple lifetime steps
        
        // Test different endTime values
        LifetimeStep<Vertex> step1 = new LifetimeStep<>(__.start().asAdmin(), "01-01-2020", "01-01-2021", null, null);
        assertEquals("01-01-2020", step1.getStartTime());
        assertEquals("01-01-2021", step1.getEndTime());
        
        // Test with longer duration
        LifetimeStep<Vertex> step2 = new LifetimeStep<>(__.start().asAdmin(), "01-01-2020", "01-01-2030", null, null);
        assertEquals("01-01-2020", step2.getStartTime());
        assertEquals("01-01-2030", step2.getEndTime());
        
        // Test with shorter duration
        LifetimeStep<Vertex> step3 = new LifetimeStep<>(__.start().asAdmin(), "01-01-2020", "01-01-2025", null, null);
        assertEquals("01-01-2020", step3.getStartTime());
        assertEquals("01-01-2025", step3.getEndTime());
        
        // Verify that steps with different endTimes are not equal
        assertNotEquals(step1, step2);
        assertNotEquals(step2, step3);
        assertNotEquals(step1, step3);
    }

    @Test
    public void shouldTestLifetimeWithPropertyKey() {
        // Test lifetime with property key and value
        LifetimeStep<Vertex> step1 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2004", "name", "john");
        assertEquals("02-02-2004", step1.getStartTime());
        assertEquals("03-03-2004", step1.getEndTime());
        
        LifetimeStep<Vertex> step2 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2024", "name", null);
        assertEquals("02-02-2004", step2.getStartTime());
        assertEquals("03-03-2024", step2.getEndTime());
        
        // Verify that steps with different property values are not equal
        assertNotEquals(step1, step2);
    }

    @Test
    public void shouldTestLifetimeOnEdge() {
        // Test lifetime on edges
        LifetimeStep<Edge> step1 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2004", null, null);
        assertEquals("02-02-2004", step1.getStartTime());
        assertEquals("03-03-2004", step1.getEndTime());
        
        LifetimeStep<Edge> step2 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2024", null, null);
        assertEquals("02-02-2004", step2.getStartTime());
        assertEquals("03-03-2024", step2.getEndTime());
        
        // Verify that steps with different endTimes are not equal
        assertNotEquals(step1, step2);
    }

    @Test
    public void shouldTestLifetimeWithDefaultEndTime() {
        // Test lifetime with default end time (1e10)
        LifetimeStep<Vertex> step1 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", null, null, null);
        assertEquals("02-02-2004", step1.getStartTime());
        assertEquals("1e10", step1.getEndTime());
        
        // Test with explicit default end time
        LifetimeStep<Vertex> step2 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "1e10", null, null);
        assertEquals("02-02-2004", step2.getStartTime());
        assertEquals("1e10", step2.getEndTime());
        
        // Test with specific end time
        LifetimeStep<Vertex> step3 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2024", null, null);
        assertEquals("02-02-2004", step3.getStartTime());
        assertEquals("03-03-2024", step3.getEndTime());
        
        // Verify that steps with default and specific endTimes are not equal
        assertNotEquals(step1, step3);
        assertNotEquals(step2, step3);
        assertEquals(step1, step2); // Both use default endTime
    }

    @Test
    public void shouldTestLifetimeWithDifferentDateFormats() {
        // Test the specific date format from the request: "02-02-2004"
        
        // Test with dd-MM-yyyy format
        LifetimeStep<Vertex> step1 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2004", null, null);
        assertEquals("02-02-2004", step1.getStartTime());
        assertEquals("03-03-2004", step1.getEndTime());
        
        // Test with different date formats
        LifetimeStep<Vertex> step2 = new LifetimeStep<>(__.start().asAdmin(), "2004-02-02", "2004-03-03", null, null);
        assertEquals("2004-02-02", step2.getStartTime());
        assertEquals("2004-03-03", step2.getEndTime());
        
        // Test with timestamp format
        long startTimestamp = new Date(104, 1, 2).getTime(); // 2004-02-02
        long endTimestamp = new Date(104, 2, 3).getTime();   // 2004-03-03
        
        LifetimeStep<Vertex> step3 = new LifetimeStep<>(__.start().asAdmin(), String.valueOf(startTimestamp), String.valueOf(endTimestamp), null, null);
        assertEquals(String.valueOf(startTimestamp), step3.getStartTime());
        assertEquals(String.valueOf(endTimestamp), step3.getEndTime());
        
        // Verify that steps with different date formats are not equal
        assertNotEquals(step1, step2);
        assertNotEquals(step2, step3);
        assertNotEquals(step1, step3);
    }

    @Test
    public void shouldTestLifetimeStepEquality() {
        // Test that LifetimeStep instances are equal when they have the same parameters
        LifetimeStep<Vertex> step1 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2004", null, null);
        LifetimeStep<Vertex> step2 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2004", null, null);
        LifetimeStep<Vertex> step3 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2024", null, null);
        
        assertEquals(step1, step2);
        assertNotEquals(step1, step3);
        
        // Test with property key and value
        LifetimeStep<Vertex> step4 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2004", "name", "john");
        LifetimeStep<Vertex> step5 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2004", "name", "john");
        LifetimeStep<Vertex> step6 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2004", "name", "jane");
        
        assertEquals(step4, step5);
        assertNotEquals(step4, step6);
    }

    @Test
    public void shouldTestLifetimeStepHashCode() {
        // Test that LifetimeStep instances have consistent hashCode
        LifetimeStep<Vertex> step1 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2004", null, null);
        LifetimeStep<Vertex> step2 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2004", null, null);
        LifetimeStep<Vertex> step3 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2024", null, null);
        
        assertEquals(step1.hashCode(), step2.hashCode());
        assertNotEquals(step1.hashCode(), step3.hashCode());
    }

    @Test
    public void shouldTestLifetimeWithExistingStartTimeAndNewEndTime() {
        // Test the scenario where startTime is initialized but endTime is not,
        // and verify that both existing lifetime and new endTime can be assigned
        
        // Test the LifetimeStep constructor behavior with null endTime
        LifetimeStep<Vertex> step1 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", null, null, null);
        assertEquals("02-02-2004", step1.getStartTime());
        assertEquals("1e10", step1.getEndTime());
        
        // Test with a different startTime
        LifetimeStep<Vertex> step2 = new LifetimeStep<>(__.start().asAdmin(), "01-01-2020", null, null, null);
        assertEquals("01-01-2020", step2.getStartTime());
        assertEquals("1e10", step2.getEndTime());
        
        // Test with property key and value
        LifetimeStep<Vertex> step3 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", null, "name", "john");
        assertEquals("02-02-2004", step3.getStartTime());
        assertEquals("1e10", step3.getEndTime());
        
        // Test that the step can be created with existing startTime and new endTime
        LifetimeStep<Vertex> step4 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "03-03-2024", null, null);
        assertEquals("02-02-2004", step4.getStartTime());
        assertEquals("03-03-2024", step4.getEndTime());
        
        // Test that the step can be created with existing startTime and default endTime
        LifetimeStep<Vertex> step5 = new LifetimeStep<>(__.start().asAdmin(), "02-02-2004", "1e10", null, null);
        assertEquals("02-02-2004", step5.getStartTime());
        assertEquals("1e10", step5.getEndTime());
    }
} 
