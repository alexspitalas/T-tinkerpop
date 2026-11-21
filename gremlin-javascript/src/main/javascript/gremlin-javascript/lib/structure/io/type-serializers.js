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

/**
 * @author Jorge Bay Gondra
 */
'use strict';

const t = require('../../process/traversal');
const ts = require('../../process/traversal-strategy');
const Bytecode = require('../../process/bytecode');
const g = require('../graph');
const utils = require('../../utils');

const valueKey = '@value';
const typeKey = '@type';

/**
 * @abstract
 */
class TypeSerializer {
  serialize() {
    throw new Error('serialize() method not implemented for ' + this.constructor.name);
  }

  deserialize() {
    throw new Error('deserialize() method not implemented for ' + this.constructor.name);
  }

  canBeUsedFor() {
    throw new Error('canBeUsedFor() method not implemented for ' + this.constructor.name);
  }
}

class NumberSerializer extends TypeSerializer {
  serialize(item) {
    if (isNaN(item)) {
      return {
        [typeKey]: 'g:Double',
        [valueKey]: 'NaN',
      };
    } else if (item === Number.POSITIVE_INFINITY) {
      return {
        [typeKey]: 'g:Double',
        [valueKey]: 'Infinity',
      };
    } else if (item === Number.NEGATIVE_INFINITY) {
      return {
        [typeKey]: 'g:Double',
        [valueKey]: '-Infinity',
      };
    }
    return item;
  }

  deserialize(obj) {
    const val = obj[valueKey];
    if (val === 'NaN') {
      return NaN;
    } else if (val === 'Infinity') {
      return Number.POSITIVE_INFINITY;
    } else if (val === '-Infinity') {
      return Number.NEGATIVE_INFINITY;
    }
    return parseFloat(val);
  }

  canBeUsedFor(value) {
    return typeof value === 'number';
  }
}

class DateSerializer extends TypeSerializer {
  serialize(item) {
    return {
      [typeKey]: 'g:Date',
      [valueKey]: item.getTime(),
    };
  }

  deserialize(obj) {
    return new Date(obj[valueKey]);
  }

  canBeUsedFor(value) {
    return value instanceof Date;
  }
}

class LongSerializer extends TypeSerializer {
  serialize(item) {
    let val;
    if (typeof item === 'bigint') {
      val =
        item >= BigInt(Number.MIN_SAFE_INTEGER) && item <= BigInt(Number.MAX_SAFE_INTEGER)
          ? Number(item)
          : item.toString();
    } else {
      val = item.value;
    }
    return {
      [typeKey]: 'g:Int64',
      [valueKey]: val,
    };
  }

  canBeUsedFor(value) {
    return value instanceof utils.Long || typeof value === 'bigint';
  }
}

class BytecodeSerializer extends TypeSerializer {
  serialize(item) {
    let bytecode = item;
    if (item instanceof t.Traversal) {
      bytecode = item.getBytecode();
    }
    const result = {};
    result[typeKey] = 'g:Bytecode';
    const resultValue = (result[valueKey] = {});
    const sources = this._serializeInstructions(bytecode.sourceInstructions);
    if (sources) {
      resultValue['source'] = sources;
    }
    const steps = this._serializeInstructions(bytecode.stepInstructions);
    if (steps) {
      resultValue['step'] = steps;
    }
    return result;
  }

  _serializeInstructions(instructions) {
    if (instructions.length === 0) {
      return null;
    }
    const result = new Array(instructions.length);
    result[0] = instructions[0];
    for (let i = 0; i < instructions.length; i++) {
      result[i] = instructions[i].map((item) => this.writer.adaptObject(item));
    }
    return result;
  }

  canBeUsedFor(value) {
    return value instanceof Bytecode || value instanceof t.Traversal;
  }
}

class PSerializer extends TypeSerializer {
  /** @param {P} item */
  serialize(item) {
    const result = {};
    result[typeKey] = 'g:P';
    const resultValue = (result[valueKey] = {
      predicate: item.operator,
    });
    if (item.other === undefined || item.other === null) {
      resultValue['value'] = this.writer.adaptObject(item.value);
    } else {
      resultValue['value'] = [this.writer.adaptObject(item.value), this.writer.adaptObject(item.other)];
    }
    return result;
  }

  canBeUsedFor(value) {
    return value instanceof t.P;
  }
}

class TextPSerializer extends TypeSerializer {
  /** @param {TextP} item */
  serialize(item) {
    const result = {};
    result[typeKey] = 'g:TextP';
    const resultValue = (result[valueKey] = {
      predicate: item.operator,
    });
    if (item.other === undefined || item.other === null) {
      resultValue['value'] = this.writer.adaptObject(item.value);
    } else {
      resultValue['value'] = [this.writer.adaptObject(item.value), this.writer.adaptObject(item.other)];
    }
    return result;
  }

  canBeUsedFor(value) {
    return value instanceof t.TextP;
  }
}

class LambdaSerializer extends TypeSerializer {
  /** @param {Function} item */
  serialize(item) {
    const lambdaDef = item();

    // check if the language is specified otherwise assume gremlin-groovy.
    const returnIsString = typeof lambdaDef === 'string';
    const script = returnIsString ? lambdaDef : lambdaDef[0];
    const lang = returnIsString ? 'gremlin-groovy' : lambdaDef[1];

    // detect argument count
    const argCount =
      lang === 'gremlin-groovy' && script.includes('->')
        ? script.substring(0, script.indexOf('->')).includes(',')
          ? 2
          : 1
        : -1;

    return {
      [typeKey]: 'g:Lambda',
      [valueKey]: {
        arguments: argCount,
        language: lang,
        script: script,
      },
    };
  }

  canBeUsedFor(value) {
    return typeof value === 'function';
  }
}

class EnumSerializer extends TypeSerializer {
  /** @param {EnumValue} item */
  serialize(item) {
    return {
      [typeKey]: 'g:' + item.typeName,
      [valueKey]: item.elementName,
    };
  }

  canBeUsedFor(value) {
    return value && value.typeName && value instanceof t.EnumValue;
  }
}

class TraverserSerializer extends TypeSerializer {
  /** @param {Traverser} item */
  serialize(item) {
    return {
      [typeKey]: 'g:Traverser',
      [valueKey]: {
        value: this.writer.adaptObject(item.object),
        bulk: this.writer.adaptObject(item.bulk),
      },
    };
  }

  deserialize(obj) {
    const value = obj[valueKey];
    return new t.Traverser(this.reader.read(value['value']), this.reader.read(value['bulk']));
  }

  canBeUsedFor(value) {
    return value instanceof t.Traverser;
  }
}

class TraversalStrategySerializer extends TypeSerializer {
  /** @param {TraversalStrategy} item */
  serialize(item) {
    const conf = {};
    for (const k in item.configuration) {
      if (item.configuration.hasOwnProperty(k)) {
        conf[k] = this.writer.adaptObject(item.configuration[k]);
      }
    }

    return {
      [typeKey]: 'g:' + item.constructor.name,
      [valueKey]: conf,
    };
  }

  canBeUsedFor(value) {
    return value instanceof ts.TraversalStrategy;
  }
}

class VertexSerializer extends TypeSerializer {
  deserialize(obj) {
    const value = obj[valueKey];
    return new g.Vertex(this.reader.read(value['id']), value['label'], this.reader.read(value['properties']));
  }

  /** @param {Vertex} item */
  serialize(item) {
    return {
      [typeKey]: 'g:Vertex',
      [valueKey]: {
        id: this.writer.adaptObject(item.id),
        label: item.label,
      },
    };
  }

  canBeUsedFor(value) {
    return value instanceof g.Vertex;
  }
}

class VertexPropertySerializer extends TypeSerializer {
  deserialize(obj) {
    const value = obj[valueKey];
    return new g.VertexProperty(
      this.reader.read(value['id']),
      value['label'],
      this.reader.read(value['value']),
      this.reader.read(value['properties']),
    );
  }
}

class PropertySerializer extends TypeSerializer {
  deserialize(obj) {
    const value = obj[valueKey];
    return new g.Property(value['key'], this.reader.read(value['value']));
  }
}

class EdgeSerializer extends TypeSerializer {
  deserialize(obj) {
    const value = obj[valueKey];
    return new g.Edge(
      this.reader.read(value['id']),
      new g.Vertex(this.reader.read(value['outV']), this.reader.read(value['outVLabel'])),
      value['label'],
      new g.Vertex(this.reader.read(value['inV']), this.reader.read(value['inVLabel'])),
      this.reader.read(value['properties']),
    );
  }

  /** @param {Edge} item */
  serialize(item) {
    return {
      [typeKey]: 'g:Edge',
      [valueKey]: {
        id: this.writer.adaptObject(item.id),
        label: item.label,
        outV: this.writer.adaptObject(item.outV.id),
        outVLabel: item.outV.label,
        inV: this.writer.adaptObject(item.inV.id),
        inVLabel: item.inV.label,
      },
    };
  }

  canBeUsedFor(value) {
    return value instanceof g.Edge;
  }
}

class PathSerializer extends TypeSerializer {
  deserialize(obj) {
    const value = obj[valueKey];
    const objects = value['objects'].map((o) => this.reader.read(o));
    return new g.Path(this.reader.read(value['labels']), objects);
  }
}

class Path3Serializer extends TypeSerializer {
  deserialize(obj) {
    const value = obj[valueKey];
    return new g.Path(this.reader.read(value['labels']), this.reader.read(value['objects']));
  }
}

class TSerializer extends TypeSerializer {
  deserialize(obj) {
    return t.t[obj[valueKey]];
  }
}

class DirectionSerializer extends TypeSerializer {
  deserialize(obj) {
    return t.direction[obj[valueKey].toLowerCase()];
  }
}

class ArraySerializer extends TypeSerializer {
  constructor(typeKey) {
    super();
    this.typeKey = typeKey;
  }

  deserialize(obj) {
    const value = obj[valueKey];
    if (!Array.isArray(value)) {
      throw new Error('Expected Array, obtained: ' + value);
    }
    return value.map((x) => this.reader.read(x));
  }

  /** @param {Array} item */
  serialize(item) {
    return {
      [typeKey]: this.typeKey,
      [valueKey]: item.map((x) => this.writer.adaptObject(x)),
    };
  }

  canBeUsedFor(value) {
    return Array.isArray(value);
  }
}

class BulkSetSerializer extends TypeSerializer {
  deserialize(obj) {
    const value = obj[valueKey];
    if (!Array.isArray(value)) {
      throw new Error('Expected Array, obtained: ' + value);
    }

    // coerce the BulkSet to List. if the bulk exceeds the int space then we can't coerce to List anyway,
    // so this query will be trouble. we'd need a legit BulkSet implementation here in js. this current
    // implementation is here to replicate the previous functionality that existed on the server side in
    // previous versions.
    let result = [];
    for (let ix = 0, iy = value.length; ix < iy; ix += 2) {
      const pair = value.slice(ix, ix + 2);
      result = result.concat(Array(this.reader.read(pair[1])).fill(this.reader.read(pair[0])));
    }

    return result;
  }
}

class MapSerializer extends TypeSerializer {
  deserialize(obj) {
    const value = obj[valueKey];
    if (!Array.isArray(value)) {
      throw new Error('Expected Array, obtained: ' + value);
    }
    const result = new Map();
    for (let i = 0; i < value.length; i += 2) {
      result.set(this.reader.read(value[i]), this.reader.read(value[i + 1]));
    }
    return result;
  }

  /** @param {Map} map */
  serialize(map) {
    const arr = [];
    map.forEach((v, k) => {
      arr.push(this.writer.adaptObject(k));
      arr.push(this.writer.adaptObject(v));
    });
    return {
      [typeKey]: 'g:Map',
      [valueKey]: arr,
    };
  }

  canBeUsedFor(value) {
    return value instanceof Map;
  }
}

class ListSerializer extends ArraySerializer {
  constructor() {
    super('g:List');
  }
}

class SetSerializer extends ArraySerializer {
  constructor() {
    super('g:Set');
  }
}

class TemporalPSerializer extends TypeSerializer {
  serialize(item) {
    const { predicateName, args } = item.toWireParts();
    const val = args.length === 1 ? this.writer.adaptObject(args[0]) : this.writer.adaptObject(args);
    return {
      [typeKey]: 'g:TemporalP',
      [valueKey]: {
        predicate: predicateName,
        value: val,
      },
    };
  }

  deserialize(obj) {
    const valObj = obj[valueKey];
    const pred = valObj.predicate;
    const val = this.reader.read(valObj.value);
    if (
      (pred === 'any' ||
        pred === 'not' ||
        pred === 'notAny' ||
        pred === 'generalized' ||
        pred === 'anyGeneralized' ||
        pred === 'notGeneralized' ||
        pred === 'notAnyGeneralized') &&
      Array.isArray(val)
    ) {
      return t.TemporalP.fromWireParts(pred, val);
    }
    return t.TemporalP.fromWireParts(pred, [val]);
  }

  canBeUsedFor(value) {
    return value instanceof t.TemporalP;
  }
}

class LifetimeSerializer extends TypeSerializer {
  serialize(item) {
    return {
      [typeKey]: 'g:Lifetime',
      [valueKey]: item.toTemporalIntervalsString(),
    };
  }

  deserialize(obj) {
    return t.Lifetime.fromTemporalIntervals(obj[valueKey]);
  }

  canBeUsedFor(value) {
    return value instanceof t.Lifetime;
  }
}

class IntervalSerializer extends TypeSerializer {
  serialize(item) {
    return {
      [typeKey]: 'g:Interval',
      [valueKey]: item.toGraphSONString(),
    };
  }

  deserialize(obj) {
    return t.Interval.fromGraphSONString(obj[valueKey]);
  }

  canBeUsedFor(value) {
    return value instanceof t.Interval;
  }
}

class GeneralizedRelationSerializer extends TypeSerializer {
  serialize(item) {
    return {
      [typeKey]: 'g:GeneralizedRelation',
      [valueKey]: item.toGraphSONString(),
    };
  }

  deserialize(obj) {
    return t.GeneralizedRelation.fromGraphSONString(obj[valueKey]);
  }

  canBeUsedFor(value) {
    return value instanceof t.GeneralizedRelation;
  }
}

class GeneralizedIntervalRelationSerializer extends TypeSerializer {
  serialize(item) {
    return {
      [typeKey]: 'g:GeneralizedIntervalRelation',
      [valueKey]: item.toGraphSONString(),
    };
  }

  deserialize(obj) {
    return t.GeneralizedIntervalRelation.fromGraphSONString(obj[valueKey]);
  }

  canBeUsedFor(value) {
    return value instanceof t.GeneralizedIntervalRelation;
  }
}

function makeEnumDeserializer(enumObj) {
  return class extends TypeSerializer {
    deserialize(obj) {
      const name = obj[valueKey];
      return enumObj[name] || enumObj[String(name).toLowerCase()];
    }
  };
}

const LifetimeModeSerializer = makeEnumDeserializer(t.lifetimeMode);
const DropModeSerializer = makeEnumDeserializer(t.dropMode);
const TemporalPathTargetSerializer = makeEnumDeserializer(t.temporalPathTarget);
const TemporalSerializer = makeEnumDeserializer(t.temporal);
const TemporalQuantifierSerializer = makeEnumDeserializer(t.temporalQuantifier);

module.exports = {
  BulkSetSerializer,
  BytecodeSerializer,
  DateSerializer,
  DirectionSerializer,
  DropModeSerializer,
  EdgeSerializer,
  EnumSerializer,
  GeneralizedIntervalRelationSerializer,
  GeneralizedRelationSerializer,
  IntervalSerializer,
  LambdaSerializer,
  LifetimeModeSerializer,
  LifetimeSerializer,
  ListSerializer,
  LongSerializer,
  MapSerializer,
  NumberSerializer,
  Path3Serializer,
  PathSerializer,
  PropertySerializer,
  PSerializer,
  TemporalPathTargetSerializer,
  TemporalPSerializer,
  TemporalQuantifierSerializer,
  TemporalSerializer,
  TextPSerializer,
  SetSerializer,
  TSerializer,
  TraverserSerializer,
  TraversalStrategySerializer,
  typeKey,
  valueKey,
  VertexPropertySerializer,
  VertexSerializer,
};
