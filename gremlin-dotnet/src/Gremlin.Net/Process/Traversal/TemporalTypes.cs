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
using System.Collections.Generic;
using System.Globalization;
using System.Linq;

namespace Gremlin.Net.Process.Traversal
{
#pragma warning disable 1591

    public class LifetimeMode : EnumWrapper
    {
        private LifetimeMode(string enumValue) : base("LifetimeMode", enumValue) { }

        public static LifetimeMode Replace => new LifetimeMode("REPLACE");
        public static LifetimeMode Add => new LifetimeMode("ADD");
        public static LifetimeMode Drop => new LifetimeMode("DROP");

        private static readonly Dictionary<string, LifetimeMode> Properties = new Dictionary<string, LifetimeMode>
        {
            { "REPLACE", Replace },
            { "ADD", Add },
            { "DROP", Drop },
        };

        public static LifetimeMode GetByValue(string value)
        {
            if (!Properties.TryGetValue(value, out var property))
                throw new ArgumentException($"No matching LifetimeMode for value '{value}'");
            return property;
        }
    }

    public class DropMode : EnumWrapper
    {
        private DropMode(string enumValue) : base("DropMode", enumValue) { }

        public static DropMode Soft => new DropMode("SOFT");
        public static DropMode Hard => new DropMode("HARD");

        private static readonly Dictionary<string, DropMode> Properties = new Dictionary<string, DropMode>
        {
            { "SOFT", Soft },
            { "HARD", Hard },
        };

        public static DropMode GetByValue(string value)
        {
            if (!Properties.TryGetValue(value, out var property))
                throw new ArgumentException($"No matching DropMode for value '{value}'");
            return property;
        }
    }

    public class TemporalPathTarget : EnumWrapper
    {
        private TemporalPathTarget(string enumValue) : base("TemporalPathTarget", enumValue) { }

        public static TemporalPathTarget EarliestArrival => new TemporalPathTarget("EARLIEST_ARRIVAL");
        public static TemporalPathTarget EarliestDeparture => new TemporalPathTarget("EARLIEST_DEPARTURE");
        public static TemporalPathTarget LatestDeparture => new TemporalPathTarget("LATEST_DEPARTURE");
        public static TemporalPathTarget LatestArrival => new TemporalPathTarget("LATEST_ARRIVAL");

        private static readonly Dictionary<string, TemporalPathTarget> Properties = new Dictionary<string, TemporalPathTarget>
        {
            { "EARLIEST_ARRIVAL", EarliestArrival },
            { "EARLIEST_DEPARTURE", EarliestDeparture },
            { "LATEST_DEPARTURE", LatestDeparture },
            { "LATEST_ARRIVAL", LatestArrival },
        };

        public static TemporalPathTarget GetByValue(string value)
        {
            if (!Properties.TryGetValue(value, out var property))
                throw new ArgumentException($"No matching TemporalPathTarget for value '{value}'");
            return property;
        }
    }

    public class Temporal : EnumWrapper
    {
        private Temporal(string enumValue) : base("Temporal", enumValue) { }

        public static Temporal Intersects => new Temporal("intersects");
        public static Temporal Disjoint => new Temporal("disjoint");
        public static Temporal Overlaps => new Temporal("overlaps");
        public static Temporal Precedes => new Temporal("precedes");
        public static Temporal Succeeds => new Temporal("succeeds");
        public static Temporal OverlappedBy => new Temporal("overlappedBy");
        public static Temporal Meets => new Temporal("meets");
        public static Temporal MetBy => new Temporal("metBy");
        public static Temporal During => new Temporal("during");
        public static Temporal Contains => new Temporal("contains");
        public static Temporal Eq => new Temporal("eq");
        public static Temporal Starts => new Temporal("starts");
        public static Temporal StartedBy => new Temporal("startedBy");
        public static Temporal Finishes => new Temporal("finishes");
        public static Temporal FinishedBy => new Temporal("finishedBy");
        public static Temporal Generalized => new Temporal("generalized");

        private static readonly Dictionary<string, Temporal> Properties = new Dictionary<string, Temporal>
        {
            { "intersects", Intersects },
            { "disjoint", Disjoint },
            { "overlaps", Overlaps },
            { "precedes", Precedes },
            { "succeeds", Succeeds },
            { "overlappedBy", OverlappedBy },
            { "meets", Meets },
            { "metBy", MetBy },
            { "during", During },
            { "contains", Contains },
            { "eq", Eq },
            { "starts", Starts },
            { "startedBy", StartedBy },
            { "finishes", Finishes },
            { "finishedBy", FinishedBy },
            { "generalized", Generalized },
        };

        public static Temporal GetByValue(string value)
        {
            if (!Properties.TryGetValue(value, out var property))
                throw new ArgumentException($"No matching Temporal for value '{value}'");
            return property;
        }
    }

    public class TemporalQuantifier : EnumWrapper
    {
        private TemporalQuantifier(string enumValue) : base("Quantifier", enumValue) { }

        public static TemporalQuantifier All => new TemporalQuantifier("ALL");
        public static TemporalQuantifier Any => new TemporalQuantifier("ANY");

        private static readonly Dictionary<string, TemporalQuantifier> Properties = new Dictionary<string, TemporalQuantifier>
        {
            { "ALL", All },
            { "ANY", Any },
        };

        public static TemporalQuantifier GetByValue(string value)
        {
            if (!Properties.TryGetValue(value, out var property))
                throw new ArgumentException($"No matching TemporalQuantifier for value '{value}'");
            return property;
        }
    }

    public sealed class Interval : IEquatable<Interval>
    {
        public long StartMillis { get; }
        public long EndMillis { get; }

        public Interval(long startMillis, long endMillis)
        {
            if (endMillis < startMillis)
                throw new ArgumentException("End time must not be before start time");
            StartMillis = startMillis;
            EndMillis = endMillis;
        }

        public bool Equals(Interval? other) =>
            other != null && StartMillis == other.StartMillis && EndMillis == other.EndMillis;

        public string ToGraphSONString() =>
            $"{StartMillis.ToString(CultureInfo.InvariantCulture)}:{EndMillis.ToString(CultureInfo.InvariantCulture)}";

        public static Interval FromGraphSONString(string text)
        {
            if (text == null) throw new ArgumentNullException(nameof(text));
            var parts = text.Split(':');
            if (parts.Length != 2) throw new ArgumentException($"Invalid Interval format: {text}");
            return new Interval(
                long.Parse(parts[0], CultureInfo.InvariantCulture),
                long.Parse(parts[1], CultureInfo.InvariantCulture));
        }

        public override bool Equals(object? obj) => Equals(obj as Interval);

        public override int GetHashCode()
        {
            unchecked { return (StartMillis.GetHashCode() * 397) ^ EndMillis.GetHashCode(); }
        }

        public override string ToString() => $"Interval({StartMillis}, {EndMillis})";
    }

    public sealed class Lifetime : IEquatable<Lifetime>
    {
        public const long MinMillis = long.MinValue;
        public const long MaxMillis = long.MaxValue;

        public static readonly Lifetime Empty = new Lifetime(Array.Empty<Interval>());
        public static readonly Lifetime Unbounded = new Lifetime(new[] { new Interval(MinMillis, MaxMillis) });

        public IReadOnlyList<Interval> Intervals { get; }

        public Lifetime(IEnumerable<Interval>? intervals)
        {
            if (intervals == null)
            {
                Intervals = Array.Empty<Interval>();
                return;
            }
            var list = new List<Interval>();
            foreach (var iv in intervals)
            {
                if (iv == null) throw new ArgumentNullException(nameof(intervals), "Interval elements cannot be null");
                list.Add(iv);
            }
            if (list.Count == 0)
            {
                Intervals = Array.Empty<Interval>();
                return;
            }
            list.Sort((a, b) =>
            {
                var cmp = a.StartMillis.CompareTo(b.StartMillis);
                return cmp != 0 ? cmp : a.EndMillis.CompareTo(b.EndMillis);
            });
            var merged = new List<Interval>(list.Count);
            var curS = list[0].StartMillis;
            var curE = list[0].EndMillis;
            for (var i = 1; i < list.Count; i++)
            {
                var nextS = list[i].StartMillis;
                var nextE = list[i].EndMillis;
                if (CanMerge(curS, curE, nextS, nextE))
                {
                    if (nextE > curE) curE = nextE;
                    if (merged.Count > 0)
                    {
                        var prev = merged[merged.Count - 1];
                        if (CanMerge(prev.StartMillis, prev.EndMillis, curS, curE))
                        {
                            merged.RemoveAt(merged.Count - 1);
                            curS = prev.StartMillis;
                            if (prev.EndMillis > curE) curE = prev.EndMillis;
                        }
                    }
                }
                else
                {
                    merged.Add(new Interval(curS, curE));
                    curS = nextS;
                    curE = nextE;
                }
            }
            merged.Add(new Interval(curS, curE));
            Intervals = merged.AsReadOnly();
        }

        private static bool CanMerge(long leftStart, long leftEnd, long rightStart, long rightEnd)
        {
            if (leftEnd < rightStart) return false;
            var leftIsHalfOpen = leftStart != leftEnd;
            var rightIsPoint = rightStart == rightEnd;
            return !(leftIsHalfOpen && rightIsPoint && leftEnd == rightStart);
        }

        public static Lifetime From(long startMillis, long endMillis) =>
            new Lifetime(new[] { new Interval(startMillis, endMillis) });

        public static Lifetime Of(params Interval[] intervals) => new Lifetime(intervals);

        public bool IsEmpty => Intervals.Count == 0;

        public bool IsUnbounded =>
            Intervals.Count == 1 && Intervals[0].StartMillis <= MinMillis && Intervals[0].EndMillis >= MaxMillis;

        public string ToTemporalIntervalsString() =>
            string.Join(",", Intervals.Select(iv =>
                $"{iv.StartMillis.ToString(CultureInfo.InvariantCulture)}:{iv.EndMillis.ToString(CultureInfo.InvariantCulture)}"));

        public static Lifetime FromTemporalIntervals(string? text)
        {
            if (string.IsNullOrEmpty(text)) return Empty;
            var parts = text!.Split(',');
            var ivs = new List<Interval>(parts.Length);
            foreach (var raw in parts)
            {
                var part = raw.Trim();
                if (part.Length == 0) continue;
                var bounds = part.Split(':');
                if (bounds.Length != 2) throw new ArgumentException($"Invalid temporalIntervals component '{part}'");
                ivs.Add(new Interval(
                    long.Parse(bounds[0], CultureInfo.InvariantCulture),
                    long.Parse(bounds[1], CultureInfo.InvariantCulture)));
            }
            return new Lifetime(ivs);
        }

        public bool Equals(Lifetime? other) =>
            other != null && Intervals.SequenceEqual(other.Intervals);

        public override bool Equals(object? obj) => Equals(obj as Lifetime);

        public override int GetHashCode()
        {
            unchecked
            {
                var hash = 17;
                foreach (var iv in Intervals)
                    hash = hash * 31 + iv.GetHashCode();
                return hash;
            }
        }

        public override string ToString() =>
            IsEmpty ? "Lifetime.EMPTY" : IsUnbounded ? "Lifetime.UNBOUNDED" : $"Lifetime({ToTemporalIntervalsString()})";
    }

    public sealed class GeneralizedRelation : IEquatable<GeneralizedRelation>
    {
        public int P { get; }
        public int Q { get; }
        public IReadOnlyList<int> Zones { get; }

        private static readonly Dictionary<string, int[]> AllenZones = new Dictionary<string, int[]>
        {
            { "precedes", new[] { 0, 0 } },
            { "meets", new[] { 0, 1 } },
            { "overlaps", new[] { 0, 2 } },
            { "finishedBy", new[] { 0, 3 } },
            { "contains", new[] { 0, 4 } },
            { "starts", new[] { 1, 2 } },
            { "eq", new[] { 1, 3 } },
            { "startedBy", new[] { 1, 4 } },
            { "during", new[] { 2, 2 } },
            { "finishes", new[] { 2, 3 } },
            { "overlappedBy", new[] { 2, 4 } },
            { "metBy", new[] { 3, 4 } },
            { "succeeds", new[] { 4, 4 } },
        };

        public GeneralizedRelation(int p, int q, params int[] zones)
        {
            if (p < 1 || q < 1) throw new ArgumentException("Arities p and q must be >= 1");
            if (zones == null || zones.Length != p) throw new ArgumentException($"Expected {p} zone values");
            var maxZone = 2 * q;
            var copy = new int[p];
            for (var i = 0; i < p; i++)
            {
                var z = zones[i];
                if (z < 0 || z > maxZone) throw new ArgumentException($"Zone index out of bounds [0, {maxZone}]: {z}");
                if (i > 0)
                {
                    var prev = copy[i - 1];
                    if (z < prev) throw new ArgumentException("Zones must be non-decreasing");
                    if ((z & 1) == 1 && z == prev) throw new ArgumentException($"Odd zone {z} may occur at most once");
                }
                copy[i] = z;
            }
            P = p;
            Q = q;
            Zones = Array.AsReadOnly(copy);
        }

        public static GeneralizedRelation FromAllen(Temporal op)
        {
            if (op == null) throw new ArgumentNullException(nameof(op));
            if (!AllenZones.TryGetValue(op.EnumValue, out var zones))
                throw new ArgumentException($"Unsupported Allen Temporal operator: {op.EnumValue}");
            return new GeneralizedRelation(2, 2, zones);
        }

        public string ToGraphSONString() =>
            $"{P.ToString(CultureInfo.InvariantCulture)},{Q.ToString(CultureInfo.InvariantCulture)}:{string.Join(",", Zones.Select(z => z.ToString(CultureInfo.InvariantCulture)))}";

        public static GeneralizedRelation FromGraphSONString(string text)
        {
            if (text == null) throw new ArgumentNullException(nameof(text));
            var parts = text.Split(':');
            if (parts.Length != 2) throw new ArgumentException($"Invalid GeneralizedRelation format: {text}");
            var pq = parts[0].Split(',');
            if (pq.Length != 2) throw new ArgumentException($"Invalid GeneralizedRelation arities: {text}");
            var zones = parts[1].Split(',').Select(s => int.Parse(s, CultureInfo.InvariantCulture)).ToArray();
            return new GeneralizedRelation(
                int.Parse(pq[0], CultureInfo.InvariantCulture),
                int.Parse(pq[1], CultureInfo.InvariantCulture),
                zones);
        }

        public bool Equals(GeneralizedRelation? other) =>
            other != null && P == other.P && Q == other.Q && Zones.SequenceEqual(other.Zones);

        public override bool Equals(object? obj) => Equals(obj as GeneralizedRelation);

        public override int GetHashCode()
        {
            unchecked
            {
                var h = (P * 397) ^ Q;
                foreach (var z in Zones) h = (h * 31) ^ z;
                return h;
            }
        }

        public override string ToString() => ToGraphSONString();
    }

    public sealed class GeneralizedIntervalRelation : IEquatable<GeneralizedIntervalRelation>
    {
        public int P { get; }
        public int Q { get; }
        public bool IsEmpty { get; }
        public GeneralizedRelation? Lower { get; }
        public GeneralizedRelation? Upper { get; }

        private GeneralizedIntervalRelation(int p, int q)
        {
            if (p < 1 || q < 1) throw new ArgumentException("Arities p and q must be >= 1");
            P = p;
            Q = q;
            IsEmpty = true;
        }

        public GeneralizedIntervalRelation(GeneralizedRelation lower, GeneralizedRelation upper)
        {
            if (lower == null) throw new ArgumentNullException(nameof(lower));
            if (upper == null) throw new ArgumentNullException(nameof(upper));
            if (lower.P != upper.P || lower.Q != upper.Q)
                throw new ArgumentException("lower and upper must have matching (p, q) arities");
            for (var i = 0; i < lower.P; i++)
            {
                if (lower.Zones[i] > upper.Zones[i])
                    throw new ArgumentException($"Lower bound must be <= upper bound in lattice order: {lower} > {upper}");
            }
            P = lower.P;
            Q = lower.Q;
            Lower = lower;
            Upper = upper;
            IsEmpty = false;
        }

        public static GeneralizedIntervalRelation Empty(int p, int q) => new GeneralizedIntervalRelation(p, q);

        public string ToGraphSONString() =>
            IsEmpty || Lower == null || Upper == null
                ? $"{P.ToString(CultureInfo.InvariantCulture)},{Q.ToString(CultureInfo.InvariantCulture)}:EMPTY"
                : $"{P.ToString(CultureInfo.InvariantCulture)},{Q.ToString(CultureInfo.InvariantCulture)}:{string.Join(",", Lower.Zones.Select(z => z.ToString(CultureInfo.InvariantCulture)))}:{string.Join(",", Upper.Zones.Select(z => z.ToString(CultureInfo.InvariantCulture)))}";

        public static GeneralizedIntervalRelation FromGraphSONString(string text)
        {
            if (text == null) throw new ArgumentNullException(nameof(text));
            var parts = text.Split(':');
            if (parts.Length < 2) throw new ArgumentException($"Invalid GeneralizedIntervalRelation format: {text}");
            var pq = parts[0].Split(',');
            if (pq.Length != 2) throw new ArgumentException($"Invalid GeneralizedIntervalRelation arities: {text}");
            var p = int.Parse(pq[0], CultureInfo.InvariantCulture);
            var q = int.Parse(pq[1], CultureInfo.InvariantCulture);
            if (parts[1] == "EMPTY")
            {
                if (parts.Length != 2)
                    throw new ArgumentException($"Invalid GeneralizedIntervalRelation format: {text}");
                return Empty(p, q);
            }
            if (parts.Length != 3) throw new ArgumentException($"Invalid GeneralizedIntervalRelation format: {text}");
            var lowerZones = parts[1].Split(',').Select(s => int.Parse(s, CultureInfo.InvariantCulture)).ToArray();
            var upperZones = parts[2].Split(',').Select(s => int.Parse(s, CultureInfo.InvariantCulture)).ToArray();
            return new GeneralizedIntervalRelation(
                new GeneralizedRelation(p, q, lowerZones),
                new GeneralizedRelation(p, q, upperZones));
        }

        public bool Equals(GeneralizedIntervalRelation? other) =>
            other != null && P == other.P && Q == other.Q && IsEmpty == other.IsEmpty
            && Equals(Lower, other.Lower) && Equals(Upper, other.Upper);

        public override bool Equals(object? obj) => Equals(obj as GeneralizedIntervalRelation);

        public override int GetHashCode()
        {
            unchecked
            {
                var h = (P * 397) ^ Q;
                h = (h * 31) ^ IsEmpty.GetHashCode();
                if (Lower != null) h = (h * 31) ^ Lower.GetHashCode();
                if (Upper != null) h = (h * 31) ^ Upper.GetHashCode();
                return h;
            }
        }

        public override string ToString() => ToGraphSONString();
    }

    public class TemporalP : P, IEquatable<TemporalP>
    {
        public Temporal BiPredicate { get; }
        public GeneralizedRelation? GeneralizedRelation { get; }
        public GeneralizedIntervalRelation? GeneralizedIntervalRelation { get; }
        public TemporalQuantifier Quantifier { get; private set; } = TemporalQuantifier.All;
        public bool IsNegated { get; private set; }

        public TemporalP(Temporal biPredicate, object? value,
            GeneralizedRelation? generalizedRelation = null,
            GeneralizedIntervalRelation? generalizedIntervalRelation = null,
            TemporalQuantifier? quantifier = null,
            bool negated = false)
            : base((biPredicate ?? throw new ArgumentNullException(nameof(biPredicate))).EnumValue, value)
        {
            BiPredicate = biPredicate;
            GeneralizedRelation = generalizedRelation;
            GeneralizedIntervalRelation = generalizedIntervalRelation;
            Quantifier = quantifier ?? TemporalQuantifier.All;
            IsNegated = negated;
        }

        public TemporalP Any()
        {
            Quantifier = TemporalQuantifier.Any;
            return this;
        }

        public TemporalP All()
        {
            Quantifier = TemporalQuantifier.All;
            return this;
        }

        public TemporalP Negate()
        {
            object? val = Value;
            if (!IsNegated && Quantifier.Equals(TemporalQuantifier.All))
            {
                if (BiPredicate.Equals(Temporal.Intersects))
                    return new TemporalP(Temporal.Disjoint, val);
                if (BiPredicate.Equals(Temporal.Disjoint))
                    return new TemporalP(Temporal.Intersects, val);
            }
            return new TemporalP(BiPredicate, val, GeneralizedRelation, GeneralizedIntervalRelation, Quantifier, !IsNegated);
        }

        internal (string PredicateName, List<object?> Args) ToWireParts()
        {
            object? val = Value;
            var isAny = Quantifier.Equals(TemporalQuantifier.Any);
            if (BiPredicate.Equals(Temporal.Generalized))
            {
                var pred = IsNegated
                    ? (isAny ? "notAnyGeneralized" : "notGeneralized")
                    : (isAny ? "anyGeneralized" : "generalized");
                if (GeneralizedRelation != null)
                    return (pred, new List<object?> { val, GeneralizedRelation });
                if (GeneralizedIntervalRelation != null)
                    return (pred, new List<object?> { val, GeneralizedIntervalRelation });
                return (pred, new List<object?> { val });
            }
            if (isAny || IsNegated)
            {
                var pred = IsNegated ? (isAny ? "notAny" : "not") : "any";
                return (pred, new List<object?> { BiPredicate, val });
            }
            return (BiPredicate.EnumValue, new List<object?> { val });
        }

        internal static TemporalP FromWireParts(string predName, object?[] args)
        {
            if ((predName == "any" || predName == "not" || predName == "notAny" || predName == "all") && args.Length == 2)
            {
                var op = args[0] is Temporal t ? t : Temporal.GetByValue(args[0]?.ToString() ?? string.Empty);
                var tp = new TemporalP(op, args[1]);
                if (predName == "any" || predName == "notAny") tp = tp.Any();
                if (predName == "not" || predName == "notAny") tp = tp.Negate();
                return tp;
            }
            if (predName == "generalized" || predName == "anyGeneralized"
                || predName == "notGeneralized" || predName == "notAnyGeneralized")
            {
                TemporalP tp;
                if (args.Length == 2 && args[1] is GeneralizedRelation gr)
                    tp = new TemporalP(Temporal.Generalized, args[0], generalizedRelation: gr);
                else if (args.Length == 2 && args[1] is GeneralizedIntervalRelation gir)
                    tp = new TemporalP(Temporal.Generalized, args[0], generalizedIntervalRelation: gir);
                else
                    tp = new TemporalP(Temporal.Generalized, args.Length == 1 ? args[0] : args);
                if (predName == "anyGeneralized" || predName == "notAnyGeneralized") tp = tp.Any();
                if (predName == "notGeneralized" || predName == "notAnyGeneralized") tp = tp.Negate();
                return tp;
            }
            return new TemporalP(Temporal.GetByValue(predName), args.Length == 1 ? args[0] : args);
        }

        public static TemporalP Intersects(object? value) => new TemporalP(Temporal.Intersects, value);
        public static TemporalP Disjoint(object? value) => new TemporalP(Temporal.Disjoint, value);
        public static TemporalP Overlaps(object? value) => new TemporalP(Temporal.Overlaps, value);
        public static TemporalP Precedes(object? value) => new TemporalP(Temporal.Precedes, value);
        public static TemporalP Succeeds(object? value) => new TemporalP(Temporal.Succeeds, value);
        public static TemporalP OverlappedBy(object? value) => new TemporalP(Temporal.OverlappedBy, value);
        public static TemporalP Meets(object? value) => new TemporalP(Temporal.Meets, value);
        public static TemporalP MetBy(object? value) => new TemporalP(Temporal.MetBy, value);
        public static TemporalP During(object? value) => new TemporalP(Temporal.During, value);
        public static TemporalP Contains(object? value) => new TemporalP(Temporal.Contains, value);
        public static TemporalP Eq(object? value) => new TemporalP(Temporal.Eq, value);
        public static TemporalP Starts(object? value) => new TemporalP(Temporal.Starts, value);
        public static TemporalP StartedBy(object? value) => new TemporalP(Temporal.StartedBy, value);
        public static TemporalP Finishes(object? value) => new TemporalP(Temporal.Finishes, value);
        public static TemporalP FinishedBy(object? value) => new TemporalP(Temporal.FinishedBy, value);

        public static TemporalP Between(Temporal lower, Temporal upper, object? value) =>
            new TemporalP(Temporal.Generalized, value,
                generalizedIntervalRelation: new GeneralizedIntervalRelation(
                    GeneralizedRelation.FromAllen(lower),
                    GeneralizedRelation.FromAllen(upper)));

        public static TemporalP Generalized(object? value)
        {
            if (value is IReadOnlyList<object?> pair && pair.Count == 2)
            {
                if (pair[1] is GeneralizedRelation gr)
                    return new TemporalP(Temporal.Generalized, pair[0], generalizedRelation: gr);
                if (pair[1] is GeneralizedIntervalRelation gir)
                    return new TemporalP(Temporal.Generalized, pair[0], generalizedIntervalRelation: gir);
            }
            return new TemporalP(Temporal.Generalized, value);
        }

        public static TemporalP Generalized(object? value, GeneralizedRelation relation) =>
            new TemporalP(Temporal.Generalized, value, generalizedRelation: relation);

        public static TemporalP Generalized(object? value, GeneralizedIntervalRelation intervalRelation) =>
            new TemporalP(Temporal.Generalized, value, generalizedIntervalRelation: intervalRelation);

        public static TemporalP AnyGeneralized(object? value) =>
            Generalized(value).Any();

        public static TemporalP AnyGeneralized(object? value, GeneralizedRelation relation) =>
            Generalized(value, relation).Any();

        public static TemporalP AnyGeneralized(object? value, GeneralizedIntervalRelation intervalRelation) =>
            Generalized(value, intervalRelation).Any();

        public static TemporalP NotGeneralized(object? value) =>
            Generalized(value).Negate();

        public static TemporalP NotGeneralized(object? value, GeneralizedRelation relation) =>
            Generalized(value, relation).Negate();

        public static TemporalP NotGeneralized(object? value, GeneralizedIntervalRelation intervalRelation) =>
            Generalized(value, intervalRelation).Negate();

        public static TemporalP NotAnyGeneralized(object? value) =>
            Generalized(value).Any().Negate();

        public static TemporalP NotAnyGeneralized(object? value, GeneralizedRelation relation) =>
            Generalized(value, relation).Any().Negate();

        public static TemporalP NotAnyGeneralized(object? value, GeneralizedIntervalRelation intervalRelation) =>
            Generalized(value, intervalRelation).Any().Negate();

        public static TemporalP Any(Temporal op, object? value) => new TemporalP(op, value).Any();
        public static TemporalP All(Temporal op, object? value) => new TemporalP(op, value).All();
        public static TemporalP Not(Temporal op, object? value) => new TemporalP(op, value).Negate();
        public static TemporalP NotAny(Temporal op, object? value) => new TemporalP(op, value).Any().Negate();

        public bool Equals(TemporalP? other)
        {
            if (other is null) return false;
            if (ReferenceEquals(this, other)) return true;
            object? thisVal = Value;
            object? otherVal = other.Value;
            return Equals(BiPredicate, other.BiPredicate)
                   && Equals(thisVal, otherVal)
                   && Equals(Quantifier, other.Quantifier)
                   && IsNegated == other.IsNegated
                   && Equals(GeneralizedRelation, other.GeneralizedRelation)
                   && Equals(GeneralizedIntervalRelation, other.GeneralizedIntervalRelation);
        }

        public override bool Equals(object? obj) => Equals(obj as TemporalP);

        public override int GetHashCode()
        {
            unchecked
            {
                object? val = Value;
                var h = BiPredicate.GetHashCode();
                h = (h * 31) ^ (val?.GetHashCode() ?? 0);
                h = (h * 31) ^ Quantifier.GetHashCode();
                h = (h * 31) ^ IsNegated.GetHashCode();
                if (GeneralizedRelation != null) h = (h * 31) ^ GeneralizedRelation.GetHashCode();
                if (GeneralizedIntervalRelation != null) h = (h * 31) ^ GeneralizedIntervalRelation.GetHashCode();
                return h;
            }
        }

        public override string ToString()
        {
            var (pred, args) = ToWireParts();
            return $"{pred}({string.Join(", ", args)})";
        }
    }

    public static class TemporalPageRank
    {
        public const string Alpha = "~tinkerpop.temporalPageRank.alpha";
        public const string Beta = "~tinkerpop.temporalPageRank.beta";
        public const string PropertyName = "~tinkerpop.temporalPageRank.propertyName";
        public const string Normalize = "~tinkerpop.temporalPageRank.normalize";
        public const string BucketMillis = "~tinkerpop.temporalPageRank.bucketMillis";
    }

    public static class TemporalShortestPath
    {
        public const string Target = "gremlin.temporalShortestPathVertexProgram.target";
        public const string InitialTime = "gremlin.temporalShortestPathVertexProgram.initialTime";
        public const string TargetVertexFilter = "gremlin.temporalShortestPathVertexProgram.targetVertexFilter";
        public const string EdgeTraversal = "gremlin.temporalShortestPathVertexProgram.edgeTraversal";
        public const string MaxDistance = "gremlin.temporalShortestPathVertexProgram.maxDistance";
        public const string IncludeEdges = "gremlin.temporalShortestPathVertexProgram.includeEdges";
    }

    public static class TemporalOptions
    {
        public const string Quantifier = "~tinkerpop.temporal.quantifier";
        public const string Any = "ANY";
        public const string All = "ALL";
    }

#pragma warning restore 1591
}
