/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

'use strict';

const { Buffer } = require('buffer');
const t = require('../../../../process/traversal');

function readHeader(buffer, expectedCode, fullyQualifiedFormat) {
  if (buffer === undefined || buffer === null || !(buffer instanceof Buffer)) {
    throw new Error('buffer is missing');
  }
  if (buffer.length < 1) {
    throw new Error('buffer is empty');
  }
  let len = 0;
  let cursor = buffer;
  if (fullyQualifiedFormat) {
    const typeCode = cursor.readUInt8();
    len++;
    if (typeCode !== expectedCode) {
      throw new Error(`unexpected {type_code}=${typeCode}`);
    }
    cursor = cursor.slice(1);
    if (cursor.length < 1) {
      throw new Error('{value_flag} is missing');
    }
    const valueFlag = cursor.readUInt8();
    len++;
    if (valueFlag === 1) {
      return { isNull: true, cursor: cursor.slice(1), len };
    }
    if (valueFlag !== 0) {
      throw new Error('unexpected {value_flag}');
    }
    cursor = cursor.slice(1);
  }
  return { isNull: false, cursor, len };
}

class TemporalPSerializer {
  constructor(ioc) {
    this.ioc = ioc;
    this.ioc.serializers[ioc.DataType.TEMPORALP] = this;
  }

  canBeUsedFor(value) {
    return value instanceof t.TemporalP;
  }

  serialize(item, fullyQualifiedFormat = true) {
    if (item === undefined || item === null) {
      if (fullyQualifiedFormat) {
        return Buffer.from([this.ioc.DataType.TEMPORALP, 0x01]);
      }
      return Buffer.from([0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]);
    }
    const bufs = [];
    if (fullyQualifiedFormat) {
      bufs.push(Buffer.from([this.ioc.DataType.TEMPORALP, 0x00]));
    }
    const { predicateName, args } = item.toWireParts();
    bufs.push(this.ioc.stringSerializer.serialize(predicateName, false));
    bufs.push(this.ioc.listSerializer.serialize(args, false));
    return Buffer.concat(bufs);
  }

  deserialize(buffer, fullyQualifiedFormat = true) {
    let cursor = buffer;
    try {
      const header = readHeader(buffer, this.ioc.DataType.TEMPORALP, fullyQualifiedFormat);
      if (header.isNull) {
        return { v: null, len: header.len };
      }
      let len = header.len;
      cursor = header.cursor;

      const { v: name, len: nameLen } = this.ioc.stringSerializer.deserialize(cursor, false);
      len += nameLen;
      cursor = cursor.slice(nameLen);

      const { v: args, len: argsLen } = this.ioc.listSerializer.deserialize(cursor, false);
      len += argsLen;

      return { v: t.TemporalP.fromWireParts(name, args), len };
    } catch (err) {
      throw this.ioc.utils.des_error({ serializer: this, args: arguments, cursor, err });
    }
  }
}

class LifetimeSerializer {
  constructor(ioc) {
    this.ioc = ioc;
    this.ioc.serializers[ioc.DataType.LIFETIME] = this;
  }

  canBeUsedFor(value) {
    return value instanceof t.Lifetime;
  }

  serialize(item, fullyQualifiedFormat = true) {
    if (item === undefined || item === null) {
      if (fullyQualifiedFormat) {
        return Buffer.from([this.ioc.DataType.LIFETIME, 0x01]);
      }
      return Buffer.from([0x00, 0x00, 0x00, 0x00]);
    }
    const count = item.intervals.length;
    const prefixLen = fullyQualifiedFormat ? 2 : 0;
    const buf = Buffer.alloc(prefixLen + 4 + count * 16);
    let offset = 0;
    if (fullyQualifiedFormat) {
      buf.writeUInt8(this.ioc.DataType.LIFETIME, offset++);
      buf.writeUInt8(0x00, offset++);
    }
    buf.writeInt32BE(count, offset);
    offset += 4;
    for (let i = 0; i < count; i++) {
      buf.writeBigInt64BE(item.intervals[i].startMillis, offset);
      offset += 8;
      buf.writeBigInt64BE(item.intervals[i].endMillis, offset);
      offset += 8;
    }
    return buf;
  }

  deserialize(buffer, fullyQualifiedFormat = true) {
    let cursor = buffer;
    try {
      const header = readHeader(buffer, this.ioc.DataType.LIFETIME, fullyQualifiedFormat);
      if (header.isNull) {
        return { v: null, len: header.len };
      }
      let len = header.len;
      cursor = header.cursor;

      if (cursor.length < 4) {
        throw new Error('unexpected {count} length');
      }
      const count = cursor.readInt32BE(0);
      if (count < 0) {
        throw new Error(`negative {count}=${count}`);
      }
      len += 4;
      cursor = cursor.slice(4);

      if (cursor.length < count * 16) {
        throw new Error('unexpected {intervals} length');
      }
      const ivs = [];
      for (let i = 0; i < count; i++) {
        const s = cursor.readBigInt64BE(i * 16);
        const e = cursor.readBigInt64BE(i * 16 + 8);
        ivs.push(new t.Interval(s, e));
      }
      len += count * 16;
      return { v: new t.Lifetime(ivs), len };
    } catch (err) {
      throw this.ioc.utils.des_error({ serializer: this, args: arguments, cursor, err });
    }
  }
}

class IntervalSerializer {
  constructor(ioc) {
    this.ioc = ioc;
    this.ioc.serializers[ioc.DataType.INTERVAL] = this;
  }

  canBeUsedFor(value) {
    return value instanceof t.Interval;
  }

  serialize(item, fullyQualifiedFormat = true) {
    if (item === undefined || item === null) {
      if (fullyQualifiedFormat) {
        return Buffer.from([this.ioc.DataType.INTERVAL, 0x01]);
      }
      return Buffer.alloc(16);
    }
    const prefixLen = fullyQualifiedFormat ? 2 : 0;
    const buf = Buffer.alloc(prefixLen + 16);
    let offset = 0;
    if (fullyQualifiedFormat) {
      buf.writeUInt8(this.ioc.DataType.INTERVAL, offset++);
      buf.writeUInt8(0x00, offset++);
    }
    buf.writeBigInt64BE(item.startMillis, offset);
    buf.writeBigInt64BE(item.endMillis, offset + 8);
    return buf;
  }

  deserialize(buffer, fullyQualifiedFormat = true) {
    let cursor = buffer;
    try {
      const header = readHeader(buffer, this.ioc.DataType.INTERVAL, fullyQualifiedFormat);
      if (header.isNull) {
        return { v: null, len: header.len };
      }
      let len = header.len;
      cursor = header.cursor;

      if (cursor.length < 16) {
        throw new Error('unexpected {interval} length');
      }
      const s = cursor.readBigInt64BE(0);
      const e = cursor.readBigInt64BE(8);
      len += 16;
      return { v: new t.Interval(s, e), len };
    } catch (err) {
      throw this.ioc.utils.des_error({ serializer: this, args: arguments, cursor, err });
    }
  }
}

class GeneralizedRelationSerializer {
  constructor(ioc) {
    this.ioc = ioc;
    this.ioc.serializers[ioc.DataType.GENERALIZEDRELATION] = this;
  }

  canBeUsedFor(value) {
    return value instanceof t.GeneralizedRelation;
  }

  serialize(item, fullyQualifiedFormat = true) {
    if (item === undefined || item === null) {
      if (fullyQualifiedFormat) {
        return Buffer.from([this.ioc.DataType.GENERALIZEDRELATION, 0x01]);
      }
      return Buffer.alloc(8);
    }
    const prefixLen = fullyQualifiedFormat ? 2 : 0;
    const buf = Buffer.alloc(prefixLen + 8 + item.p * 4);
    let offset = 0;
    if (fullyQualifiedFormat) {
      buf.writeUInt8(this.ioc.DataType.GENERALIZEDRELATION, offset++);
      buf.writeUInt8(0x00, offset++);
    }
    buf.writeInt32BE(item.p, offset);
    offset += 4;
    buf.writeInt32BE(item.q, offset);
    offset += 4;
    for (let i = 0; i < item.p; i++) {
      buf.writeInt32BE(item.zones[i], offset);
      offset += 4;
    }
    return buf;
  }

  deserialize(buffer, fullyQualifiedFormat = true) {
    let cursor = buffer;
    try {
      const header = readHeader(buffer, this.ioc.DataType.GENERALIZEDRELATION, fullyQualifiedFormat);
      if (header.isNull) {
        return { v: null, len: header.len };
      }
      let len = header.len;
      cursor = header.cursor;

      if (cursor.length < 8) {
        throw new Error('unexpected {p,q} length');
      }
      const p = cursor.readInt32BE(0);
      const q = cursor.readInt32BE(4);
      if (p < 1 || q < 1) {
        throw new Error(`invalid arities (${p}, ${q})`);
      }
      len += 8;
      cursor = cursor.slice(8);

      if (cursor.length < p * 4) {
        throw new Error('unexpected {zones} length');
      }
      const zones = [];
      for (let i = 0; i < p; i++) {
        zones.push(cursor.readInt32BE(i * 4));
      }
      len += p * 4;
      return { v: new t.GeneralizedRelation(p, q, zones), len };
    } catch (err) {
      throw this.ioc.utils.des_error({ serializer: this, args: arguments, cursor, err });
    }
  }
}

class GeneralizedIntervalRelationSerializer {
  constructor(ioc) {
    this.ioc = ioc;
    this.ioc.serializers[ioc.DataType.GENERALIZEDINTERVALRELATION] = this;
  }

  canBeUsedFor(value) {
    return value instanceof t.GeneralizedIntervalRelation;
  }

  serialize(item, fullyQualifiedFormat = true) {
    if (item === undefined || item === null) {
      if (fullyQualifiedFormat) {
        return Buffer.from([this.ioc.DataType.GENERALIZEDINTERVALRELATION, 0x01]);
      }
      return Buffer.alloc(9);
    }
    const prefixLen = fullyQualifiedFormat ? 2 : 0;
    const zonesBytes = item.isEmpty() ? 0 : item.p * 8;
    const buf = Buffer.alloc(prefixLen + 9 + zonesBytes);
    let offset = 0;
    if (fullyQualifiedFormat) {
      buf.writeUInt8(this.ioc.DataType.GENERALIZEDINTERVALRELATION, offset++);
      buf.writeUInt8(0x00, offset++);
    }
    buf.writeInt32BE(item.p, offset);
    offset += 4;
    buf.writeInt32BE(item.q, offset);
    offset += 4;
    buf.writeUInt8(item.isEmpty() ? 0x01 : 0x00, offset++);
    if (!item.isEmpty()) {
      for (let i = 0; i < item.p; i++) {
        buf.writeInt32BE(item.lower.zones[i], offset);
        offset += 4;
      }
      for (let i = 0; i < item.p; i++) {
        buf.writeInt32BE(item.upper.zones[i], offset);
        offset += 4;
      }
    }
    return buf;
  }

  deserialize(buffer, fullyQualifiedFormat = true) {
    let cursor = buffer;
    try {
      const header = readHeader(buffer, this.ioc.DataType.GENERALIZEDINTERVALRELATION, fullyQualifiedFormat);
      if (header.isNull) {
        return { v: null, len: header.len };
      }
      let len = header.len;
      cursor = header.cursor;

      if (cursor.length < 9) {
        throw new Error('unexpected {p,q,empty} length');
      }
      const p = cursor.readInt32BE(0);
      const q = cursor.readInt32BE(4);
      if (p < 1 || q < 1) {
        throw new Error(`invalid arities (${p}, ${q})`);
      }
      const empty = cursor.readUInt8(8) !== 0;
      len += 9;
      cursor = cursor.slice(9);

      if (empty) {
        return { v: t.GeneralizedIntervalRelation.empty(p, q), len };
      }
      if (cursor.length < p * 8) {
        throw new Error('unexpected {lower,upper} length');
      }
      const lowerZones = [];
      const upperZones = [];
      for (let i = 0; i < p; i++) {
        lowerZones.push(cursor.readInt32BE(i * 4));
      }
      for (let i = 0; i < p; i++) {
        upperZones.push(cursor.readInt32BE((p + i) * 4));
      }
      len += p * 8;
      return {
        v: new t.GeneralizedIntervalRelation(
          new t.GeneralizedRelation(p, q, lowerZones),
          new t.GeneralizedRelation(p, q, upperZones),
        ),
        len,
      };
    } catch (err) {
      throw this.ioc.utils.des_error({ serializer: this, args: arguments, cursor, err });
    }
  }
}

module.exports = {
  TemporalPSerializer,
  LifetimeSerializer,
  IntervalSerializer,
  GeneralizedRelationSerializer,
  GeneralizedIntervalRelationSerializer,
};
