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

using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using Gremlin.Net.Process.Traversal.Step.Util;
using Gremlin.Net.Process.Traversal.Strategy;
using Gremlin.Net.Process.Traversal.Strategy.Decoration;

namespace Gremlin.Net.Process.Traversal.Translator
{
    /// <summary>
    ///     Converts bytecode to a Groovy string of Gremlin.
    /// </summary>
    public class GroovyTranslator
    {
        private GroovyTranslator(string traversalSource)
        {
            TraversalSource = traversalSource;
        }

        /// <summary>
        ///     Creates the translator.
        /// </summary>
        /// <param name="traversalSource">The traversal source for the traversal to be translated.</param>
        /// <returns>The created translator instance.</returns>
        public static GroovyTranslator Of(string traversalSource)
        {
            return new GroovyTranslator(traversalSource);
        }

        /// <summary>
        ///     Get the language that the translator is converting the traversal byte code to.
        /// </summary>
        public string TargetLanguage => "gremlin-groovy";

        /// <summary>
        ///     Gets the <see cref="TraversalSource"/> representation rooting this translator.
        ///     This is typically a "g".
        /// </summary>
        public string TraversalSource { get; }

        /// <summary>
        ///     Translate <see cref="ITraversal"/> into gremlin-groovy.
        /// </summary>
        /// <param name="traversal">The traversal to translate.</param>
        /// <param name="isChildTraversal">Whether this is an anonymous traversal (started via '__').</param>
        /// <returns>The translated gremlin-groovy traversal.</returns>
        public string Translate(ITraversal traversal, bool isChildTraversal = false)
        {
            return Translate(traversal.Bytecode, isChildTraversal);
        }
        
        /// <summary>
        ///     Translate <see cref="Bytecode"/> into gremlin-groovy.
        /// </summary>
        /// <param name="bytecode">The bytecode representing traversal source and traversal manipulations.</param>
        /// <param name="isChildTraversal">Whether this is an anonymous traversal (started via '__').</param>
        /// <returns>The translated gremlin-groovy traversal.</returns>
        public string Translate(Bytecode bytecode, bool isChildTraversal = false)
        {
            var sb = new StringBuilder(isChildTraversal ? "__" : TraversalSource);
            
            foreach (var step in bytecode.SourceInstructions)
            {
                sb.Append(TranslateStep(step));
            }
            
            foreach (var step in bytecode.StepInstructions)
            {
                sb.Append(TranslateStep(step));
            }

            return sb.ToString();
        }

        private string TranslateStep(Instruction step)
        {
            if (step.OperatorName == "with")
            {
                return $".{step.OperatorName}({TranslateWithArguments(step.Arguments)})";
            }
            return $".{step.OperatorName}({TranslateArguments(step.Arguments)})";
        }
        
        private string TranslateWithArguments(dynamic?[] arguments)
        {
            if (arguments[0] == WithOptions.Tokens)
            {
                return string.Join(", ", arguments.Select(a => PropertyMapOptionsTranslation[a]));
            }
            if (arguments[0] == WithOptions.Indexer)
            {
                return string.Join(", ", arguments.Select(a => IndexOptionsTranslation[a]));
            }
            return string.Join(", ", arguments.Select(TranslateArgument));
        }

        private static readonly Dictionary<object, string> PropertyMapOptionsTranslation = new Dictionary<object, string>
        {
            { WithOptions.Tokens, "WithOptions.tokens" },
            { WithOptions.None, "WithOptions.none" },
            { WithOptions.Ids, "WithOptions.ids" },
            { WithOptions.Labels, "WithOptions.labels" },
            { WithOptions.Keys, "WithOptions.keys" },
            { WithOptions.Values, "WithOptions.values" },
            { WithOptions.All, "WithOptions.all" }
        };
        
        private static readonly Dictionary<object, string> IndexOptionsTranslation = new Dictionary<object, string>
        {
            { WithOptions.Indexer, "WithOptions.indexer" },
            { WithOptions.List, "WithOptions.list" },
            { WithOptions.Map, "WithOptions.map" }
        };

        private string TranslateArguments(IEnumerable<object?> arguments) =>
            string.Join(", ", arguments.Select(TranslateArgument));

        private string TranslateArgument(object? argument)
        {
            return argument switch
            {
                null => "null",
                string str => $"'{str}'",
                char c => $"'{c}'",
                bool b => b ? "true" : "false",
                DateTimeOffset dto => TranslateDateTimeOffset(dto),
                DateTime dt => TranslateDateTimeOffset(dt),
                Guid guid => $"UUID.fromString('{guid}')",
                TemporalP tp => TranslateTemporalP(tp),
                P p => TranslateP(p),
                Lifetime lt => TranslateLifetime(lt),
                Interval iv => TranslateInterval(iv),
                GeneralizedRelation gr => TranslateGeneralizedRelation(gr),
                GeneralizedIntervalRelation gir => TranslateGeneralizedIntervalRelation(gir),
                LifetimeMode lm => $"LifetimeMode.{lm.EnumValue}",
                DropMode dm => $"DropMode.{dm.EnumValue}",
                TemporalPathTarget tpt => $"TemporalPathTarget.{tpt.EnumValue}",
                Temporal t => $"Temporal.{t.EnumValue}",
                TemporalQuantifier tq => $"Temporal.Quantifier.{tq.EnumValue}",
                IDictionary dict => TranslateDictionary(dict),
                IEnumerable e => TranslateCollection(e),
                ITraversal t => TranslateTraversal(t),
                AbstractTraversalStrategy strategy => TranslateStrategy(strategy),
                _ => Convert.ToString(argument, CultureInfo.InvariantCulture)!
            };
        }
        
        private static string TranslateDateTimeOffset(DateTimeOffset dto)
        {
            var year = dto.Year - 1900;
            var month = dto.Month - 1;
            var dayOfMonth = dto.Day;
            var hour = dto.Hour;
            var minute = dto.Minute;
            var second = dto.Second;
            return $"new Date({year}, {month}, {dayOfMonth}, {hour}, {minute}, {second})";
        }

        private string TranslateTemporalP(TemporalP tp)
        {
            var sb = new StringBuilder();
            sb.Append("TemporalP.").Append(tp.BiPredicate.EnumValue).Append('(');
            object? value = tp.Value;
            sb.Append(TranslateArgument(value));
            if (tp.GeneralizedRelation != null)
            {
                sb.Append(',').Append(TranslateGeneralizedRelation(tp.GeneralizedRelation));
            }
            else if (tp.GeneralizedIntervalRelation != null)
            {
                sb.Append(',').Append(TranslateGeneralizedIntervalRelation(tp.GeneralizedIntervalRelation));
            }
            sb.Append(')');
            if (tp.Quantifier.Equals(TemporalQuantifier.Any))
            {
                sb.Append(".any()");
            }
            if (tp.IsNegated)
            {
                sb.Append(".negate()");
            }
            return sb.ToString();
        }

        private static string TranslateLifetime(Lifetime lt)
        {
            if (lt.IsEmpty)
                return "Lifetime.empty()";
            if (lt.IsUnbounded)
                return "Lifetime.UNBOUNDED";
            return $"Lifetime.fromTemporalIntervals('{lt.ToTemporalIntervalsString()}')";
        }

        private static string TranslateInterval(Interval iv) =>
            $"new Lifetime.Interval({iv.StartMillis.ToString(CultureInfo.InvariantCulture)}L,{iv.EndMillis.ToString(CultureInfo.InvariantCulture)}L)";

        private static string TranslateGeneralizedRelation(GeneralizedRelation gr)
        {
            var sb = new StringBuilder("new GeneralizedRelation(")
                .Append(gr.P.ToString(CultureInfo.InvariantCulture))
                .Append(',')
                .Append(gr.Q.ToString(CultureInfo.InvariantCulture));
            for (var i = 0; i < gr.Zones.Count; i++)
            {
                sb.Append(',').Append(gr.Zones[i].ToString(CultureInfo.InvariantCulture));
            }
            sb.Append(')');
            return sb.ToString();
        }

        private static string TranslateGeneralizedIntervalRelation(GeneralizedIntervalRelation gir)
        {
            if (gir.IsEmpty || gir.Lower == null || gir.Upper == null)
            {
                return $"GeneralizedIntervalRelation.empty({gir.P.ToString(CultureInfo.InvariantCulture)},{gir.Q.ToString(CultureInfo.InvariantCulture)})";
            }
            return $"new GeneralizedIntervalRelation({TranslateGeneralizedRelation(gir.Lower)},{TranslateGeneralizedRelation(gir.Upper)})";
        }
        
        private string TranslateP(P p)
        {
            return p.Other == null
                ? $"P.{p.OperatorName}({TranslateArgument(p.Value)})"
                : $"P.{p.OperatorName}({TranslateArgument(p.Value)}, {TranslateArgument(p.Other)})";
        }
        
        private string TranslateDictionary(IDictionary dict)
        {
            var kvStrings = new List<string>(dict.Count);
            foreach (DictionaryEntry kv in dict)
            {
                kvStrings.Add($"{TranslateArgument(kv.Key)}: {TranslateArgument(kv.Value)}");
            }            

            return $"[{string.Join(", ", kvStrings)}]";
        }

        private string TranslateCollection(IEnumerable enumerable) =>
            $"[{TranslateArguments(enumerable.Cast<object>().ToArray())}]";
        
        private string TranslateTraversal(ITraversal traversal) => Translate(traversal.Bytecode, true);

        private string TranslateStrategy(AbstractTraversalStrategy strategy)
        {
            var config = string.Join(", ",
                strategy.Configuration.Select(opt =>
                    $"{(strategy.StrategyName == nameof(OptionsStrategy) ? $"'{opt.Key}'" : opt.Key)}: {TranslateArgument(opt.Value)}"));

            return $"new {strategy.StrategyName}({config})";
        }
    }
}