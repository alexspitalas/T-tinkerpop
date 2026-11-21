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

using System.IO;
using System.Threading;
using System.Threading.Tasks;
using Gremlin.Net.Process.Traversal;

namespace Gremlin.Net.Structure.IO.GraphBinary.Types
{
#pragma warning disable 1591

    internal static class TemporalSerializerBounds
    {
        internal static void EnsureRemainingBytes(Stream stream, long requiredBytes, string label)
        {
            if (requiredBytes < 0)
                throw new IOException($"Invalid negative byte count for {label}: {requiredBytes}");
            if (stream.CanSeek)
            {
                var remaining = stream.Length - stream.Position;
                if (remaining < requiredBytes)
                    throw new IOException(
                        $"Truncated stream while reading {label}: requires {requiredBytes} bytes, but only {remaining} bytes remain");
            }
        }

        internal static void ValidateElementCount(Stream stream, int count, int minBytesPerElement, string label)
        {
            if (count < 0)
                throw new IOException($"Invalid negative {label}: {count}");
            if (stream.CanSeek)
            {
                var remaining = stream.Length - stream.Position;
                var required = (long)count * minBytesPerElement;
                if (remaining < 0 || required > remaining)
                    throw new IOException(
                        $"Invalid {label} {count}: requires at least {required} bytes, but only {remaining} bytes remain in stream");
            }
        }
    }

    public class TemporalPSerializer : SimpleTypeSerializer<TemporalP>
    {
        public TemporalPSerializer() : base(DataType.TemporalP) { }

        protected override async Task WriteValueAsync(TemporalP value, Stream stream, GraphBinaryWriter writer,
            CancellationToken cancellationToken = default)
        {
            var (predicateName, args) = value.ToWireParts();
            await writer.WriteNonNullableValueAsync(predicateName, stream, cancellationToken).ConfigureAwait(false);
            await writer.WriteNonNullableValueAsync(args.Count, stream, cancellationToken).ConfigureAwait(false);
            foreach (var arg in args)
            {
                await writer.WriteAsync(arg, stream, cancellationToken).ConfigureAwait(false);
            }
        }

        protected override async Task<TemporalP> ReadValueAsync(Stream stream, GraphBinaryReader reader,
            CancellationToken cancellationToken = default)
        {
            var predicateName = (string)await reader.ReadNonNullableValueAsync<string>(stream, cancellationToken)
                .ConfigureAwait(false);
            TemporalSerializerBounds.EnsureRemainingBytes(stream, 4, "TemporalP argument count");
            var argsLength = (int)await reader.ReadNonNullableValueAsync<int>(stream, cancellationToken)
                .ConfigureAwait(false);
            TemporalSerializerBounds.ValidateElementCount(stream, argsLength, 2, "TemporalP argument count");
            var args = new object?[argsLength];
            for (var i = 0; i < argsLength; i++)
            {
                args[i] = await reader.ReadAsync(stream, cancellationToken).ConfigureAwait(false);
            }
            return TemporalP.FromWireParts(predicateName, args);
        }
    }

    public class LifetimeSerializer : SimpleTypeSerializer<Lifetime>
    {
        public LifetimeSerializer() : base(DataType.Lifetime) { }

        protected override async Task WriteValueAsync(Lifetime value, Stream stream, GraphBinaryWriter writer,
            CancellationToken cancellationToken = default)
        {
            await stream.WriteIntAsync(value.Intervals.Count, cancellationToken).ConfigureAwait(false);
            foreach (var iv in value.Intervals)
            {
                await stream.WriteLongAsync(iv.StartMillis, cancellationToken).ConfigureAwait(false);
                await stream.WriteLongAsync(iv.EndMillis, cancellationToken).ConfigureAwait(false);
            }
        }

        protected override async Task<Lifetime> ReadValueAsync(Stream stream, GraphBinaryReader reader,
            CancellationToken cancellationToken = default)
        {
            TemporalSerializerBounds.EnsureRemainingBytes(stream, 4, "Lifetime interval count");
            var count = await stream.ReadIntAsync(cancellationToken).ConfigureAwait(false);
            TemporalSerializerBounds.ValidateElementCount(stream, count, 16, "Lifetime interval count");
            if (count == 0) return Lifetime.Empty;
            var ivs = new Interval[count];
            for (var i = 0; i < count; i++)
            {
                var s = await stream.ReadLongAsync(cancellationToken).ConfigureAwait(false);
                var e = await stream.ReadLongAsync(cancellationToken).ConfigureAwait(false);
                ivs[i] = new Interval(s, e);
            }
            return new Lifetime(ivs);
        }
    }

    public class IntervalSerializer : SimpleTypeSerializer<Interval>
    {
        public IntervalSerializer() : base(DataType.Interval) { }

        protected override async Task WriteValueAsync(Interval value, Stream stream, GraphBinaryWriter writer,
            CancellationToken cancellationToken = default)
        {
            await stream.WriteLongAsync(value.StartMillis, cancellationToken).ConfigureAwait(false);
            await stream.WriteLongAsync(value.EndMillis, cancellationToken).ConfigureAwait(false);
        }

        protected override async Task<Interval> ReadValueAsync(Stream stream, GraphBinaryReader reader,
            CancellationToken cancellationToken = default)
        {
            TemporalSerializerBounds.EnsureRemainingBytes(stream, 16, "Interval");
            var s = await stream.ReadLongAsync(cancellationToken).ConfigureAwait(false);
            var e = await stream.ReadLongAsync(cancellationToken).ConfigureAwait(false);
            return new Interval(s, e);
        }
    }

    public class GeneralizedRelationSerializer : SimpleTypeSerializer<GeneralizedRelation>
    {
        public GeneralizedRelationSerializer() : base(DataType.GeneralizedRelation) { }

        protected override async Task WriteValueAsync(GeneralizedRelation value, Stream stream, GraphBinaryWriter writer,
            CancellationToken cancellationToken = default)
        {
            await stream.WriteIntAsync(value.P, cancellationToken).ConfigureAwait(false);
            await stream.WriteIntAsync(value.Q, cancellationToken).ConfigureAwait(false);
            for (var i = 0; i < value.P; i++)
            {
                await stream.WriteIntAsync(value.Zones[i], cancellationToken).ConfigureAwait(false);
            }
        }

        protected override async Task<GeneralizedRelation> ReadValueAsync(Stream stream, GraphBinaryReader reader,
            CancellationToken cancellationToken = default)
        {
            TemporalSerializerBounds.EnsureRemainingBytes(stream, 8, "GeneralizedRelation arities");
            var p = await stream.ReadIntAsync(cancellationToken).ConfigureAwait(false);
            var q = await stream.ReadIntAsync(cancellationToken).ConfigureAwait(false);
            if (p < 1 || q < 1) throw new IOException($"Invalid GeneralizedRelation arities: ({p}, {q})");
            TemporalSerializerBounds.ValidateElementCount(stream, p, 4, "GeneralizedRelation arity p");
            var zones = new int[p];
            for (var i = 0; i < p; i++)
            {
                zones[i] = await stream.ReadIntAsync(cancellationToken).ConfigureAwait(false);
            }
            return new GeneralizedRelation(p, q, zones);
        }
    }

    public class GeneralizedIntervalRelationSerializer : SimpleTypeSerializer<GeneralizedIntervalRelation>
    {
        public GeneralizedIntervalRelationSerializer() : base(DataType.GeneralizedIntervalRelation) { }

        protected override async Task WriteValueAsync(GeneralizedIntervalRelation value, Stream stream, GraphBinaryWriter writer,
            CancellationToken cancellationToken = default)
        {
            await stream.WriteIntAsync(value.P, cancellationToken).ConfigureAwait(false);
            await stream.WriteIntAsync(value.Q, cancellationToken).ConfigureAwait(false);
            await stream.WriteByteAsync(value.IsEmpty ? (byte)1 : (byte)0, cancellationToken).ConfigureAwait(false);
            if (!value.IsEmpty && value.Lower != null && value.Upper != null)
            {
                for (var i = 0; i < value.P; i++)
                {
                    await stream.WriteIntAsync(value.Lower.Zones[i], cancellationToken).ConfigureAwait(false);
                }
                for (var i = 0; i < value.P; i++)
                {
                    await stream.WriteIntAsync(value.Upper.Zones[i], cancellationToken).ConfigureAwait(false);
                }
            }
        }

        protected override async Task<GeneralizedIntervalRelation> ReadValueAsync(Stream stream, GraphBinaryReader reader,
            CancellationToken cancellationToken = default)
        {
            TemporalSerializerBounds.EnsureRemainingBytes(stream, 9, "GeneralizedIntervalRelation header");
            var p = await stream.ReadIntAsync(cancellationToken).ConfigureAwait(false);
            var q = await stream.ReadIntAsync(cancellationToken).ConfigureAwait(false);
            if (p < 1 || q < 1) throw new IOException($"Invalid GeneralizedIntervalRelation arities: ({p}, {q})");
            var empty = await stream.ReadByteAsync(cancellationToken).ConfigureAwait(false) != 0;
            if (empty) return GeneralizedIntervalRelation.Empty(p, q);
            TemporalSerializerBounds.ValidateElementCount(stream, p, 8, "GeneralizedIntervalRelation arity p");
            var lowerZones = new int[p];
            var upperZones = new int[p];
            for (var i = 0; i < p; i++)
            {
                lowerZones[i] = await stream.ReadIntAsync(cancellationToken).ConfigureAwait(false);
            }
            for (var i = 0; i < p; i++)
            {
                upperZones[i] = await stream.ReadIntAsync(cancellationToken).ConfigureAwait(false);
            }
            return new GeneralizedIntervalRelation(
                new GeneralizedRelation(p, q, lowerZones),
                new GeneralizedRelation(p, q, upperZones));
        }
    }

#pragma warning restore 1591
}
