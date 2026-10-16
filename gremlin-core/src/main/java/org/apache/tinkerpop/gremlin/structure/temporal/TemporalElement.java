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

/**
 * An interface for graph elements or element decorators that directly hold or cache a {@link Lifetime}.
 * Implementing this interface avoids expensive repeated deserialization and parsing of temporal property strings
 * during graph traversals.
 */
public interface TemporalElement {

    /**
     * Retrieves the element's lifetime directly in O(1) without parsing property strings.
     *
     * @return the {@link Lifetime} of the element, or {@code null} if not cached or not applicable.
     */
    Lifetime getLifetime();

    /**
     * Sets or updates the in-memory cached {@link Lifetime} on this element.
     *
     * @param lifetime the new {@link Lifetime} to cache.
     */
    default void setLifetime(final Lifetime lifetime) {}
}
