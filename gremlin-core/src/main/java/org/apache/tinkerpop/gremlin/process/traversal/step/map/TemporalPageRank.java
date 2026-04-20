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

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Graph;

/**
 * Configuration options to be passed to the {@link GraphTraversal#with(String, Object)} step on
 * {@link GraphTraversal#temporalPageRank()}.
 */
public final class TemporalPageRank {

    private TemporalPageRank() {
    }

    /**
     * Configures continuation probability in the Temporal PageRank stream scan.
     */
    public static final String alpha = Graph.Hidden.hide("tinkerpop.temporalPageRank.alpha");

    /**
     * Configures active temporal-walk mass retention at the source vertex.
     */
    public static final String beta = Graph.Hidden.hide("tinkerpop.temporalPageRank.beta");

    /**
     * Configures the edge property that contains each temporal edge lifetime start.
     */
    public static final String startTimeProperty = Graph.Hidden.hide("tinkerpop.temporalPageRank.startTimeProperty");

    /**
     * Configures the edge property that contains each temporal edge lifetime end.
     */
    public static final String endTimeProperty = Graph.Hidden.hide("tinkerpop.temporalPageRank.endTimeProperty");

    /**
     * Configures the name of the vertex property within which to store the Temporal PageRank value.
     */
    public static final String propertyName = Graph.Hidden.hide("tinkerpop.temporalPageRank.propertyName");

    /**
     * Configures whether Temporal PageRank values are normalized after the stream scan.
     */
    public static final String normalize = Graph.Hidden.hide("tinkerpop.temporalPageRank.normalize");
}
