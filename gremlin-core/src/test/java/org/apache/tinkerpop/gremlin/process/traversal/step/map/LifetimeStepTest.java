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
} 
