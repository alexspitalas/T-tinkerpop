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
package org.apache.tinkerpop.gremlin.process.computer.traversal.step.map;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TemporalPathFilterStep;
import org.apache.tinkerpop.gremlin.structure.Graph;

/**
 * Configuration options to be passed to the {@link GraphTraversal#with(String, Object)} step on
 * {@link GraphTraversal#temporalPageRank()}. The {@link #filter} value accepts
 * {@link TemporalPathFilterStep.TemporalPathType} or its enum name as a {@link String}.
 */
public final class TemporalPageRank {

    public static final String times = Graph.Hidden.hide("tinkerpop.temporalPageRank.times");
    public static final String edges = Graph.Hidden.hide("tinkerpop.temporalPageRank.edges");
    public static final String propertyName = Graph.Hidden.hide("tinkerpop.temporalPageRank.propertyName");
    public static final String filter = Graph.Hidden.hide("tinkerpop.temporalPageRank.filter");
    public static final String minDelay = Graph.Hidden.hide("tinkerpop.temporalPageRank.minDelay");
    public static final String maxDelay = Graph.Hidden.hide("tinkerpop.temporalPageRank.maxDelay");

    private TemporalPageRank() {
    }
}
