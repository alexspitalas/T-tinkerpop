#region License

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

#endregion

using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using Gremlin.Net.Process.Traversal;

namespace Gremlin.Net.Structure.IO.GraphSON
{
    internal class TemporalPSerializer : IGraphSONSerializer, IGraphSONDeserializer
    {
        public Dictionary<string, dynamic> Dictify(dynamic predicate, GraphSONWriter writer)
        {
            TemporalP tp = predicate;
            var (predName, args) = tp.ToWireParts();
            dynamic? valObj = args.Count == 1
                ? writer.ToDict(args[0])
                : writer.ToDict(args);
            var dict = new Dictionary<string, dynamic>
            {
                { "predicate", predName },
                { "value", valObj! }
            };
            return GraphSONUtil.ToTypedValue("TemporalP", dict);
        }

        public dynamic Objectify(JsonElement graphsonObject, GraphSONReader reader)
        {
            var pred = graphsonObject.GetProperty("predicate").GetString() ??
                       throw new IOException("Read null predicate in g:TemporalP");
            object? val = reader.ToObject(graphsonObject.GetProperty("value"));
            if ((pred == "any" || pred == "not" || pred == "notAny" || pred == "generalized"
                 || pred == "anyGeneralized" || pred == "notGeneralized" || pred == "notAnyGeneralized")
                && val is IEnumerable enumerable && !(val is string))
            {
                var list = new List<object?>();
                foreach (var item in enumerable)
                {
                    list.Add(item);
                }
                return TemporalP.FromWireParts(pred, list.ToArray());
            }
            return TemporalP.FromWireParts(pred, new[] { val });
        }
    }

    internal class LifetimeSerializer : IGraphSONSerializer, IGraphSONDeserializer
    {
        public Dictionary<string, dynamic> Dictify(dynamic value, GraphSONWriter writer)
        {
            Lifetime lt = value;
            return GraphSONUtil.ToTypedValue("Lifetime", lt.ToTemporalIntervalsString());
        }

        public dynamic Objectify(JsonElement graphsonObject, GraphSONReader reader)
        {
            return Lifetime.FromTemporalIntervals(graphsonObject.GetString());
        }
    }

    internal class IntervalSerializer : IGraphSONSerializer, IGraphSONDeserializer
    {
        public Dictionary<string, dynamic> Dictify(dynamic value, GraphSONWriter writer)
        {
            Interval iv = value;
            return GraphSONUtil.ToTypedValue("Interval", iv.ToGraphSONString());
        }

        public dynamic Objectify(JsonElement graphsonObject, GraphSONReader reader)
        {
            return Interval.FromGraphSONString(graphsonObject.GetString() ??
                                               throw new IOException("Read null string for g:Interval"));
        }
    }

    internal class GeneralizedRelationSerializer : IGraphSONSerializer, IGraphSONDeserializer
    {
        public Dictionary<string, dynamic> Dictify(dynamic value, GraphSONWriter writer)
        {
            GeneralizedRelation rel = value;
            return GraphSONUtil.ToTypedValue("GeneralizedRelation", rel.ToGraphSONString());
        }

        public dynamic Objectify(JsonElement graphsonObject, GraphSONReader reader)
        {
            return GeneralizedRelation.FromGraphSONString(graphsonObject.GetString() ??
                                                          throw new IOException("Read null string for g:GeneralizedRelation"));
        }
    }

    internal class GeneralizedIntervalRelationSerializer : IGraphSONSerializer, IGraphSONDeserializer
    {
        public Dictionary<string, dynamic> Dictify(dynamic value, GraphSONWriter writer)
        {
            GeneralizedIntervalRelation rel = value;
            return GraphSONUtil.ToTypedValue("GeneralizedIntervalRelation", rel.ToGraphSONString());
        }

        public dynamic Objectify(JsonElement graphsonObject, GraphSONReader reader)
        {
            return GeneralizedIntervalRelation.FromGraphSONString(graphsonObject.GetString() ??
                                                                  throw new IOException("Read null string for g:GeneralizedIntervalRelation"));
        }
    }

    internal class LifetimeModeDeserializer : IGraphSONDeserializer
    {
        public dynamic Objectify(JsonElement graphsonObject, GraphSONReader reader) =>
            LifetimeMode.GetByValue(graphsonObject.GetString() ??
                                    throw new IOException($"Read null but expected a {nameof(LifetimeMode)} string representation"));
    }

    internal class DropModeDeserializer : IGraphSONDeserializer
    {
        public dynamic Objectify(JsonElement graphsonObject, GraphSONReader reader) =>
            DropMode.GetByValue(graphsonObject.GetString() ??
                                throw new IOException($"Read null but expected a {nameof(DropMode)} string representation"));
    }

    internal class TemporalPathTargetDeserializer : IGraphSONDeserializer
    {
        public dynamic Objectify(JsonElement graphsonObject, GraphSONReader reader) =>
            TemporalPathTarget.GetByValue(graphsonObject.GetString() ??
                                          throw new IOException($"Read null but expected a {nameof(TemporalPathTarget)} string representation"));
    }

    internal class TemporalDeserializer : IGraphSONDeserializer
    {
        public dynamic Objectify(JsonElement graphsonObject, GraphSONReader reader) =>
            Temporal.GetByValue(graphsonObject.GetString() ??
                                throw new IOException($"Read null but expected a {nameof(Temporal)} string representation"));
    }

    internal class TemporalQuantifierDeserializer : IGraphSONDeserializer
    {
        public dynamic Objectify(JsonElement graphsonObject, GraphSONReader reader) =>
            TemporalQuantifier.GetByValue(graphsonObject.GetString() ??
                                          throw new IOException($"Read null but expected a {nameof(TemporalQuantifier)} string representation"));
    }
}
