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
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.junit.Test;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GetEndTimeStepTest extends StepTest {

    @Override
    protected List<Traversal> getTraversals() {
        return Collections.singletonList(__.getEndTime());
    }

    @Test
    public void shouldMapElementToEndTime() {
        final Date endTime = new Date(2000L);
        final Element element = mock(Element.class);
        final Traverser.Admin<Element> traverser = mock(Traverser.Admin.class);
        final GetEndTimeStep<Element> step = new GetEndTimeStep<>(__.start().asAdmin());
        final Property<Object> endTimeProperty = property(endTime);

        when(element.property(Lifetime.END_TIME)).thenReturn(endTimeProperty);
        when(traverser.get()).thenReturn(element);

        assertEquals(endTime, step.map(traverser));
    }

    @Test
    public void shouldMapMissingEndTimeToMaxEndTime() {
        final Element element = mock(Element.class);
        final Traverser.Admin<Element> traverser = mock(Traverser.Admin.class);
        final GetEndTimeStep<Element> step = new GetEndTimeStep<>(__.start().asAdmin());

        when(element.property(Lifetime.END_TIME)).thenReturn(Property.empty());
        when(traverser.get()).thenReturn(element);

        assertEquals(Long.MAX_VALUE, step.map(traverser).getTime());
    }

    private static <V> Property<V> property(final V value) {
        final Property<V> property = mock(Property.class);
        when(property.isPresent()).thenReturn(true);
        when(property.value()).thenReturn(value);
        when(property.orElse(null)).thenReturn(value);
        return property;
    }
}
