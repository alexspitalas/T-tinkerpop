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

const itemDone = Object.freeze({ value: null, done: true });
const asyncIteratorSymbol = Symbol.asyncIterator || Symbol('@@asyncIterator');

class Traversal {
  constructor(graph, traversalStrategies, bytecode) {
    this.graph = graph;
    this.traversalStrategies = traversalStrategies;
    this.bytecode = bytecode;
    /** @type {Array<Traverser>} */
    this.traversers = null;
    this.sideEffects = null;
    this._traversalStrategiesPromise = null;
    this._traversersIteratorIndex = 0;
  }

  /**
   * Async iterable method implementation.
   */
  [asyncIteratorSymbol]() {
    return this;
  }

  /** @returns {Bytecode} */
  getBytecode() {
    return this.bytecode;
  }

  /**
   * Returns an Array containing the traverser objects.
   * @returns {Promise.<Array>}
   */
  toList() {
    return this._applyStrategies().then(() => {
      const result = [];
      let it;
      while ((it = this._getNext()) && !it.done) {
        result.push(it.value);
      }
      return result;
    });
  }

  /**
   * Determines if there are any more items to iterate from the traversal.
   * @returns {Promise.<boolean>}
   */
  hasNext() {
    return this._applyStrategies().then(
      () =>
        this.traversers &&
        this.traversers.length > 0 &&
        this._traversersIteratorIndex < this.traversers.length &&
        this.traversers[this._traversersIteratorIndex].bulk > 0,
    );
  }

  /**
   * Iterates all Traverser instances in the traversal.
   * @returns {Promise}
   */
  iterate() {
    this.bytecode.addStep('none');
    return this._applyStrategies().then(() => {
      let it;
      while ((it = this._getNext()) && !it.done) {
        //
      }
    });
  }

  /**
   * Async iterator method implementation.
   * Returns a promise containing an iterator item.
   * @returns {Promise.<{value, done}>}
   */
  next() {
    return this._applyStrategies().then(() => this._getNext());
  }

  /**
   * Synchronous iterator of traversers including
   * @private
   */
  _getNext() {
    while (this.traversers && this._traversersIteratorIndex < this.traversers.length) {
      const traverser = this.traversers[this._traversersIteratorIndex];
      if (traverser.bulk > 0) {
        traverser.bulk--;
        return { value: traverser.object, done: false };
      }
      this._traversersIteratorIndex++;
    }
    return itemDone;
  }

  _applyStrategies() {
    if (this._traversalStrategiesPromise) {
      // Apply strategies only once
      return this._traversalStrategiesPromise;
    }
    return (this._traversalStrategiesPromise = this.traversalStrategies.applyStrategies(this));
  }

  /**
   * Returns step instructions during JSON serialization
   * @returns {Array}
   */
  toJSON() {
    return this.bytecode.stepInstructions;
  }

  /**
   * Returns the Bytecode JSON representation of the traversal
   * @returns {String}
   */
  toString() {
    return this.bytecode.toString();
  }
}

class IO {
  static get graphml() {
    return 'graphml';
  }

  static get graphson() {
    return 'graphson';
  }

  static get gryo() {
    return 'gryo';
  }

  static get reader() {
    return '~tinkerpop.io.reader';
  }

  static get registry() {
    return '~tinkerpop.io.registry';
  }

  static get writer() {
    return '~tinkerpop.io.writer';
  }
}

// eslint-disable-next-line no-unused-vars
class ConnectedComponent {
  static get component() {
    return 'gremlin.connectedComponentVertexProgram.component';
  }

  static get edges() {
    return '~tinkerpop.connectedComponent.edges';
  }

  static get propertyName() {
    return '~tinkerpop.connectedComponent.propertyName';
  }
}

// eslint-disable-next-line no-unused-vars
class ShortestPath {
  static get distance() {
    return '~tinkerpop.shortestPath.distance';
  }

  static get edges() {
    return '~tinkerpop.shortestPath.edges';
  }

  static get includeEdges() {
    return '~tinkerpop.shortestPath.includeEdges';
  }

  static get maxDistance() {
    return '~tinkerpop.shortestPath.maxDistance';
  }

  static get target() {
    return '~tinkerpop.shortestPath.target';
  }
}

// eslint-disable-next-line no-unused-vars
class PageRank {
  static get edges() {
    return '~tinkerpop.pageRank.edges';
  }

  static get propertyName() {
    return '~tinkerpop.pageRank.propertyName';
  }

  static get times() {
    return '~tinkerpop.pageRank.times';
  }
}

// eslint-disable-next-line no-unused-vars
class PeerPressure {
  static get edges() {
    return '~tinkerpop.peerPressure.edges';
  }

  static get propertyName() {
    return '~tinkerpop.peerPressure.propertyName';
  }

  static get times() {
    return '~tinkerpop.peerPressure.times';
  }
}

class P {
  /**
   * Represents an operation.
   * @constructor
   */
  constructor(operator, value, other) {
    this.operator = operator;
    this.value = value;
    this.other = other;
  }

  /**
   * Returns the string representation of the instance.
   * @returns {string}
   */
  toString() {
    function formatValue(value) {
      if (Array.isArray(value)) {
        const acc = [];
        for (const item of value) {
          acc.push(formatValue(item));
        }
        return acc;
      }
      if (value && typeof value === 'string') {
        return `'${value}'`;
      }
      return value;
    }

    if (this.other === undefined || this.other === null) {
      return this.operator + '(' + formatValue(this.value) + ')';
    }
    return this.operator + '(' + formatValue(this.value) + ', ' + formatValue(this.other) + ')';
  }

  and(arg) {
    return new P('and', this, arg);
  }

  or(arg) {
    return new P('or', this, arg);
  }

  static within(...args) {
    if (args.length === 1 && Array.isArray(args[0])) {
      return new P('within', args[0], null);
    }
    return new P('within', args, null);
  }

  static without(...args) {
    if (args.length === 1 && Array.isArray(args[0])) {
      return new P('without', args[0], null);
    }
    return new P('without', args, null);
  }

  /** @param {...Object} args */
  static between(...args) {
    return createP('between', args);
  }

  /** @param {...Object} args */
  static eq(...args) {
    return createP('eq', args);
  }

  /** @param {...Object} args */
  static gt(...args) {
    return createP('gt', args);
  }

  /** @param {...Object} args */
  static gte(...args) {
    return createP('gte', args);
  }

  /** @param {...Object} args */
  static inside(...args) {
    return createP('inside', args);
  }

  /** @param {...Object} args */
  static lt(...args) {
    return createP('lt', args);
  }

  /** @param {...Object} args */
  static lte(...args) {
    return createP('lte', args);
  }

  /** @param {...Object} args */
  static neq(...args) {
    return createP('neq', args);
  }

  /** @param {...Object} args */
  static not(...args) {
    return createP('not', args);
  }

  /** @param {...Object} args */
  static outside(...args) {
    return createP('outside', args);
  }

  /** @param {...Object} args */
  static test(...args) {
    return createP('test', args);
  }
}

function createP(operator, args) {
  args.unshift(null, operator);
  return new (Function.prototype.bind.apply(P, args))();
}

class TextP {
  /**
   * Represents an operation.
   * @constructor
   */
  constructor(operator, value, other) {
    this.operator = operator;
    this.value = value;
    this.other = other;
  }

  /**
   * Returns the string representation of the instance.
   * @returns {string}
   */
  toString() {
    function formatValue(value) {
      if (value && typeof value === 'string') {
        return `'${value}'`;
      }
      return value;
    }

    if (this.other === undefined) {
      return this.operator + '(' + formatValue(this.value) + ')';
    }
    return this.operator + '(' + formatValue(this.value) + ', ' + formatValue(this.other) + ')';
  }

  and(arg) {
    return new P('and', this, arg);
  }

  or(arg) {
    return new P('or', this, arg);
  }

  /** @param {...Object} args */
  static containing(...args) {
    return createTextP('containing', args);
  }

  /** @param {...Object} args */
  static endingWith(...args) {
    return createTextP('endingWith', args);
  }

  /** @param {...Object} args */
  static notContaining(...args) {
    return createTextP('notContaining', args);
  }

  /** @param {...Object} args */
  static notEndingWith(...args) {
    return createTextP('notEndingWith', args);
  }

  /** @param {...Object} args */
  static notStartingWith(...args) {
    return createTextP('notStartingWith', args);
  }

  /** @param {...Object} args */
  static startingWith(...args) {
    return createTextP('startingWith', args);
  }

  /** @param {...Object} args */
  static regex(...args) {
    return createTextP('regex', args);
  }

  /** @param {...Object} args */
  static notRegex(...args) {
    return createTextP('notRegex', args);
  }
}

function createTextP(operator, args) {
  args.unshift(null, operator);
  return new (Function.prototype.bind.apply(TextP, args))();
}

class Traverser {
  constructor(object, bulk) {
    this.object = object;
    this.bulk = bulk || 1;
  }
}

class TraversalSideEffects {}

const withOptions = {
  tokens: '~tinkerpop.valueMap.tokens',
  none: 0,
  ids: 1,
  labels: 2,
  keys: 4,
  values: 8,
  all: 15,
  indexer: '~tinkerpop.index.indexer',
  list: 0,
  map: 1,
};

function toEnum(typeName, keys) {
  const result = {};
  keys.split(' ').forEach((k) => {
    let jsKey = k;
    if (jsKey === jsKey.toUpperCase()) {
      jsKey = jsKey.toLowerCase();
    }
    result[jsKey] = new EnumValue(typeName, k);
  });
  return result;
}

const directionAlias = {
  from_: 'out',
  to: 'in',
};

// for direction enums, maps the same EnumValue object to the enum aliases after creating them
function toDirectionEnum(typeName, keys) {
  const result = toEnum(typeName, keys);
  Object.keys(directionAlias).forEach((k) => {
    result[k] = result[directionAlias[k]];
  });
  return result;
}

class EnumValue {
  constructor(typeName, elementName) {
    this.typeName = typeName;
    this.elementName = elementName;
  }

  toString() {
    return this.elementName;
  }
}

function toDualCaseEnum(typeName, keys) {
  const result = {};
  keys.split(' ').forEach((k) => {
    const val = new EnumValue(typeName, k);
    result[k] = val;
    if (k === k.toUpperCase()) {
      result[k.toLowerCase()] = val;
    }
  });
  return result;
}

const lifetimeMode = toDualCaseEnum('LifetimeMode', 'REPLACE ADD DROP');
const dropMode = toDualCaseEnum('DropMode', 'SOFT HARD');
const temporalPathTarget = toDualCaseEnum(
  'TemporalPathTarget',
  'EARLIEST_ARRIVAL EARLIEST_DEPARTURE LATEST_DEPARTURE LATEST_ARRIVAL',
);
const temporalQuantifier = toDualCaseEnum('Quantifier', 'ALL ANY');
const temporal = toEnum(
  'Temporal',
  'intersects disjoint overlaps precedes succeeds overlappedBy meets metBy during contains eq starts startedBy finishes finishedBy generalized',
);

const MIN_MILLIS = -9223372036854775808n;
const MAX_MILLIS = 9223372036854775807n;
const STRICT_INT_REGEX = /^-?\d+$/;

function assertSignedInt64Millis(val, label = 'Temporal value') {
  if (val < MIN_MILLIS || val > MAX_MILLIS) {
    throw new RangeError(`${label} out of signed 64-bit long range [${MIN_MILLIS}, ${MAX_MILLIS}]: ${val}`);
  }
  return val;
}

function parseStrictBigInt(str, context) {
  if (typeof str !== 'string' || !STRICT_INT_REGEX.test(str)) {
    throw new Error(`Invalid integer '${str}' in ${context}`);
  }
  return BigInt(str);
}

function parseStrictInt(str, context) {
  if (typeof str !== 'string' || !STRICT_INT_REGEX.test(str)) {
    throw new Error(`Invalid integer '${str}' in ${context}`);
  }
  const n = Number(str);
  if (!Number.isSafeInteger(n)) {
    throw new Error(`Integer out of range '${str}' in ${context}`);
  }
  return n;
}

function toEpochMillisBigInt(value, defaultMillis) {
  if (value === undefined || value === null) {
    if (defaultMillis !== undefined) {
      return defaultMillis;
    }
    throw new Error('Temporal value cannot be null or undefined');
  }
  if (typeof value === 'bigint') {
    return assertSignedInt64Millis(value);
  }
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) {
      throw new Error(`Invalid temporal number: ${value}`);
    }
    return assertSignedInt64Millis(BigInt(Math.trunc(value)));
  }
  if (value instanceof Date) {
    const ms = value.getTime();
    if (!Number.isFinite(ms)) {
      throw new Error('Invalid Date for temporal value');
    }
    return assertSignedInt64Millis(BigInt(ms));
  }
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!trimmed) {
      throw new Error('Temporal string cannot be empty');
    }
    if (/^[+-]?\d+$/.test(trimmed)) {
      return assertSignedInt64Millis(BigInt(trimmed));
    }
    const ms = Date.parse(trimmed);
    if (Number.isNaN(ms)) {
      throw new Error(`Invalid temporal string: ${value}`);
    }
    return assertSignedInt64Millis(BigInt(ms));
  }
  throw new Error(`Unsupported temporal value type: ${typeof value}`);
}

function canMergeRange(leftStart, leftEnd, rightStart, rightEnd) {
  if (leftEnd < rightStart) {
    return false;
  }
  const leftIsHalfOpen = leftStart !== leftEnd;
  const rightIsPoint = rightStart === rightEnd;
  return !(leftIsHalfOpen && rightIsPoint && leftEnd === rightStart);
}

class Interval {
  constructor(startMillis, endMillis) {
    const s = toEpochMillisBigInt(startMillis);
    const e = toEpochMillisBigInt(endMillis);
    if (e < s) {
      throw new Error('End time must not be before start time');
    }
    this.startMillis = s;
    this.endMillis = e;
  }

  toGraphSONString() {
    return `${this.startMillis}:${this.endMillis}`;
  }

  static fromGraphSONString(text) {
    if (typeof text !== 'string') {
      throw new Error(`Invalid Interval format: ${text}`);
    }
    const parts = text.split(':');
    if (parts.length !== 2) {
      throw new Error(`Invalid Interval format: ${text}`);
    }
    return new Interval(
      parseStrictBigInt(parts[0], `Interval '${text}'`),
      parseStrictBigInt(parts[1], `Interval '${text}'`),
    );
  }

  equals(other) {
    return other instanceof Interval && this.startMillis === other.startMillis && this.endMillis === other.endMillis;
  }

  toString() {
    return `Interval(${this.startMillis}, ${this.endMillis})`;
  }
}

class Lifetime {
  constructor(intervals = []) {
    if (!intervals || intervals.length === 0) {
      this.intervals = Object.freeze([]);
      return;
    }
    const parsed = intervals.map((iv) => {
      if (iv instanceof Interval) {
        return [iv.startMillis, iv.endMillis];
      }
      if (Array.isArray(iv) && iv.length === 2) {
        const s = toEpochMillisBigInt(iv[0], MIN_MILLIS);
        const e = toEpochMillisBigInt(iv[1], MAX_MILLIS);
        if (e < s) {
          throw new Error('End time must not be before start time');
        }
        return [s, e];
      }
      throw new Error('Expected Interval or [start, end] pair');
    });
    parsed.sort((a, b) => {
      if (a[0] < b[0]) {
        return -1;
      }
      if (a[0] > b[0]) {
        return 1;
      }
      if (a[1] < b[1]) {
        return -1;
      }
      if (a[1] > b[1]) {
        return 1;
      }
      return 0;
    });
    const merged = [];
    let [curS, curE] = parsed[0];
    for (let i = 1; i < parsed.length; i++) {
      const [nextS, nextE] = parsed[i];
      if (canMergeRange(curS, curE, nextS, nextE)) {
        if (nextE > curE) {
          curE = nextE;
        }
        if (merged.length > 0) {
          const prev = merged[merged.length - 1];
          if (canMergeRange(prev[0], prev[1], curS, curE)) {
            merged.pop();
            curS = prev[0];
            if (prev[1] > curE) {
              curE = prev[1];
            }
          }
        }
      } else {
        merged.push([curS, curE]);
        curS = nextS;
        curE = nextE;
      }
    }
    merged.push([curS, curE]);
    this.intervals = Object.freeze(merged.map(([s, e]) => new Interval(s, e)));
  }

  static empty() {
    return new Lifetime([]);
  }

  static unbounded() {
    return new Lifetime([new Interval(MIN_MILLIS, MAX_MILLIS)]);
  }

  static from(start, end = null) {
    const s = toEpochMillisBigInt(start, MIN_MILLIS);
    const e = toEpochMillisBigInt(end, MAX_MILLIS);
    return new Lifetime([new Interval(s, e)]);
  }

  static of(...intervals) {
    return new Lifetime(intervals);
  }

  static fromTemporalIntervals(text) {
    if (text === null || text === undefined || text === '') {
      return Lifetime.empty();
    }
    if (typeof text !== 'string') {
      throw new Error(`Invalid temporalIntervals format: ${text}`);
    }
    const trimmed = text.trim();
    if (!trimmed) {
      return Lifetime.empty();
    }
    const ivs = trimmed
      .split(',')
      .map((p) => p.trim())
      .filter(Boolean)
      .map((part) => {
        const bounds = part.split(':');
        if (bounds.length !== 2) {
          throw new Error(`Invalid temporalIntervals component '${part}'`);
        }
        return new Interval(
          parseStrictBigInt(bounds[0], `temporalIntervals component '${part}'`),
          parseStrictBigInt(bounds[1], `temporalIntervals component '${part}'`),
        );
      });
    return new Lifetime(ivs);
  }

  toTemporalIntervalsString() {
    return this.intervals.map((iv) => `${iv.startMillis}:${iv.endMillis}`).join(',');
  }

  isEmpty() {
    return this.intervals.length === 0;
  }

  isUnbounded() {
    return (
      this.intervals.length === 1 &&
      this.intervals[0].startMillis <= MIN_MILLIS &&
      this.intervals[0].endMillis >= MAX_MILLIS
    );
  }

  equals(other) {
    if (!(other instanceof Lifetime) || this.intervals.length !== other.intervals.length) {
      return false;
    }
    for (let i = 0; i < this.intervals.length; i++) {
      if (!this.intervals[i].equals(other.intervals[i])) {
        return false;
      }
    }
    return true;
  }

  toString() {
    return `lifetime[${this.intervals.map((iv) => `${iv.startMillis},${iv.endMillis}`).join(' | ')}]`;
  }
}

Lifetime.MIN_MILLIS = MIN_MILLIS;
Lifetime.MAX_MILLIS = MAX_MILLIS;

const ALLEN_ZONES = {
  precedes: [0, 0],
  meets: [0, 1],
  overlaps: [0, 2],
  finishedBy: [0, 3],
  contains: [0, 4],
  starts: [1, 2],
  eq: [1, 3],
  startedBy: [1, 4],
  during: [2, 2],
  finishes: [2, 3],
  overlappedBy: [2, 4],
  metBy: [3, 4],
  succeeds: [4, 4],
};

class GeneralizedRelation {
  constructor(p, q, zones) {
    if (!Number.isInteger(p) || p < 1 || !Number.isInteger(q) || q < 1) {
      throw new Error('Arities p and q must be integers >= 1');
    }
    if (!Array.isArray(zones) || zones.length !== p) {
      throw new Error(`Expected ${p} zone values`);
    }
    const maxZone = 2 * q;
    const copy = [];
    for (let i = 0; i < p; i++) {
      const z = zones[i];
      if (!Number.isInteger(z) || z < 0 || z > maxZone) {
        throw new Error(`Zone index out of bounds [0, ${maxZone}]: ${z}`);
      }
      if (i > 0) {
        const prev = copy[i - 1];
        if (z < prev) {
          throw new Error('Zones must be non-decreasing');
        }
        if ((z & 1) === 1 && z === prev) {
          throw new Error(`Odd zone ${z} may occur at most once`);
        }
      }
      copy.push(z);
    }
    this.p = p;
    this.q = q;
    this.zones = Object.freeze(copy);
  }

  static of(q, ...zones) {
    return new GeneralizedRelation(zones.length, q, zones);
  }

  static identity(p) {
    const zones = [];
    for (let i = 0; i < p; i++) {
      zones.push(2 * i + 1);
    }
    return new GeneralizedRelation(p, p, zones);
  }

  static fromAllen(op) {
    const name = op instanceof EnumValue ? op.elementName : String(op);
    const zones = ALLEN_ZONES[name];
    if (!zones) {
      throw new Error(`Unsupported Allen Temporal operator: ${name}`);
    }
    return new GeneralizedRelation(2, 2, zones);
  }

  toGraphSONString() {
    return `${this.p},${this.q}:${this.zones.join(',')}`;
  }

  static fromGraphSONString(text) {
    if (typeof text !== 'string') {
      throw new Error(`Invalid GeneralizedRelation format: ${text}`);
    }
    const parts = text.split(':');
    if (parts.length !== 2) {
      throw new Error(`Invalid GeneralizedRelation format: ${text}`);
    }
    const pqParts = parts[0].split(',');
    if (pqParts.length !== 2) {
      throw new Error(`Invalid GeneralizedRelation arity format: ${text}`);
    }
    const p = parseStrictInt(pqParts[0], `GeneralizedRelation '${text}'`);
    const q = parseStrictInt(pqParts[1], `GeneralizedRelation '${text}'`);
    const zones = parts[1].split(',').map((s) => parseStrictInt(s, `GeneralizedRelation '${text}'`));
    return new GeneralizedRelation(p, q, zones);
  }

  equals(other) {
    if (!(other instanceof GeneralizedRelation) || this.p !== other.p || this.q !== other.q) {
      return false;
    }
    for (let i = 0; i < this.zones.length; i++) {
      if (this.zones[i] !== other.zones[i]) {
        return false;
      }
    }
    return true;
  }

  toString() {
    return `Pi(${this.p},${this.q})[${this.zones.join(',')}]`;
  }
}

class GeneralizedIntervalRelation {
  constructor(lower, upper, p = null, q = null, empty = false) {
    if (empty) {
      if (!Number.isInteger(p) || p < 1 || !Number.isInteger(q) || q < 1) {
        throw new Error('Arities p and q must be integers >= 1');
      }
      this.p = p;
      this.q = q;
      this.lower = null;
      this.upper = null;
      this.empty = true;
    } else {
      if (!(lower instanceof GeneralizedRelation) || !(upper instanceof GeneralizedRelation)) {
        throw new Error('lower and upper must be GeneralizedRelation instances');
      }
      if (lower.p !== upper.p || lower.q !== upper.q) {
        throw new Error('lower and upper must have matching (p, q) arities');
      }
      for (let i = 0; i < lower.p; i++) {
        if (lower.zones[i] > upper.zones[i]) {
          throw new Error(`Lower bound must be <= upper bound in lattice order: ${lower} vs ${upper}`);
        }
      }
      this.p = lower.p;
      this.q = lower.q;
      this.lower = lower;
      this.upper = upper;
      this.empty = false;
    }
  }

  static empty(p, q) {
    return new GeneralizedIntervalRelation(null, null, p, q, true);
  }

  isEmpty() {
    return this.empty;
  }

  toGraphSONString() {
    if (this.empty) {
      return `${this.p},${this.q}:EMPTY`;
    }
    return `${this.p},${this.q}:${this.lower.zones.join(',')}:${this.upper.zones.join(',')}`;
  }

  static fromGraphSONString(text) {
    if (typeof text !== 'string') {
      throw new Error(`Invalid GeneralizedIntervalRelation format: ${text}`);
    }
    const parts = text.split(':');
    if (parts.length !== 2 && parts.length !== 3) {
      throw new Error(`Invalid GeneralizedIntervalRelation format: ${text}`);
    }
    const pqParts = parts[0].split(',');
    if (pqParts.length !== 2) {
      throw new Error(`Invalid GeneralizedIntervalRelation arity format: ${text}`);
    }
    const p = parseStrictInt(pqParts[0], `GeneralizedIntervalRelation '${text}'`);
    const q = parseStrictInt(pqParts[1], `GeneralizedIntervalRelation '${text}'`);
    if (parts.length === 2) {
      if (parts[1] !== 'EMPTY') {
        throw new Error(`Invalid GeneralizedIntervalRelation format: ${text}`);
      }
      return GeneralizedIntervalRelation.empty(p, q);
    }
    const lowerZones = parts[1].split(',').map((s) => parseStrictInt(s, `GeneralizedIntervalRelation '${text}'`));
    const upperZones = parts[2].split(',').map((s) => parseStrictInt(s, `GeneralizedIntervalRelation '${text}'`));
    return new GeneralizedIntervalRelation(
      new GeneralizedRelation(p, q, lowerZones),
      new GeneralizedRelation(p, q, upperZones),
    );
  }

  equals(other) {
    if (
      !(other instanceof GeneralizedIntervalRelation) ||
      this.p !== other.p ||
      this.q !== other.q ||
      this.empty !== other.empty
    ) {
      return false;
    }
    if (this.empty) {
      return true;
    }
    return this.lower.equals(other.lower) && this.upper.equals(other.upper);
  }

  toString() {
    if (this.empty) {
      return `IntervalPi(${this.p},${this.q})[EMPTY]`;
    }
    return `IntervalPi(${this.p},${this.q})[${this.lower} .. ${this.upper}]`;
  }
}

class TemporalP extends P {
  constructor(
    biPredicate,
    value,
    {
      generalizedRelation = null,
      generalizedIntervalRelation = null,
      quantifier = temporalQuantifier.ALL,
      negated = false,
    } = {},
  ) {
    const op = typeof biPredicate === 'string' ? temporal[biPredicate] : biPredicate;
    if (!(op instanceof EnumValue) || op.typeName !== 'Temporal') {
      throw new Error(`biPredicate must be a Temporal enum value, got ${biPredicate}`);
    }
    let resolvedValue = value;
    let resolvedRel = generalizedRelation;
    let resolvedIRel = generalizedIntervalRelation;
    if (
      op === temporal.generalized &&
      resolvedRel === null &&
      resolvedIRel === null &&
      Array.isArray(value) &&
      value.length === 2
    ) {
      const [target, spec] = value;
      if (spec instanceof GeneralizedRelation) {
        resolvedValue = target;
        resolvedRel = spec;
      } else if (spec instanceof GeneralizedIntervalRelation) {
        resolvedValue = target;
        resolvedIRel = spec;
      }
    }
    super(op.elementName, resolvedValue, null);
    this.biPredicate = op;
    this.generalizedRelation = resolvedRel;
    this.generalizedIntervalRelation = resolvedIRel;
    this.quantifier = quantifier;
    this.negated = Boolean(negated);
  }

  any() {
    this.quantifier = temporalQuantifier.ANY;
    return this;
  }

  all() {
    this.quantifier = temporalQuantifier.ALL;
    return this;
  }

  negate() {
    if (!this.negated && this.quantifier === temporalQuantifier.ALL) {
      if (this.biPredicate === temporal.intersects) {
        return new TemporalP(temporal.disjoint, this.value);
      }
      if (this.biPredicate === temporal.disjoint) {
        return new TemporalP(temporal.intersects, this.value);
      }
    }
    return new TemporalP(this.biPredicate, this.value, {
      generalizedRelation: this.generalizedRelation,
      generalizedIntervalRelation: this.generalizedIntervalRelation,
      quantifier: this.quantifier,
      negated: !this.negated,
    });
  }

  toWireParts() {
    const isAny = this.quantifier === temporalQuantifier.ANY;
    const isNeg = this.negated;
    if (this.biPredicate === temporal.generalized) {
      let predName;
      if (isNeg) {
        predName = isAny ? 'notAnyGeneralized' : 'notGeneralized';
      } else {
        predName = isAny ? 'anyGeneralized' : 'generalized';
      }
      if (this.generalizedRelation) {
        return { predicateName: predName, args: [this.value, this.generalizedRelation] };
      }
      if (this.generalizedIntervalRelation) {
        return { predicateName: predName, args: [this.value, this.generalizedIntervalRelation] };
      }
      return { predicateName: predName, args: [this.value] };
    }
    if (isAny || isNeg) {
      const predName = isNeg ? (isAny ? 'notAny' : 'not') : 'any';
      return { predicateName: predName, args: [this.biPredicate, this.value] };
    }
    return { predicateName: this.biPredicate.elementName, args: [this.value] };
  }

  static fromWireParts(predName, args) {
    if (
      (predName === 'any' || predName === 'not' || predName === 'notAny' || predName === 'all') &&
      args.length === 2
    ) {
      const op = args[0] instanceof EnumValue ? args[0] : temporal[String(args[0])];
      let tp = new TemporalP(op, args[1]);
      if (predName === 'any' || predName === 'notAny') {
        tp = tp.any();
      }
      if (predName === 'not' || predName === 'notAny') {
        tp = tp.negate();
      }
      return tp;
    }
    if (
      predName === 'generalized' ||
      predName === 'anyGeneralized' ||
      predName === 'notGeneralized' ||
      predName === 'notAnyGeneralized'
    ) {
      let tp;
      if (args.length === 2 && args[1] instanceof GeneralizedRelation) {
        tp = TemporalP.generalized(args[0], args[1]);
      } else if (args.length === 2 && args[1] instanceof GeneralizedIntervalRelation) {
        tp = TemporalP.generalized(args[0], args[1]);
      } else {
        tp = TemporalP.generalized(args.length === 1 ? args[0] : args);
      }
      if (predName === 'anyGeneralized' || predName === 'notAnyGeneralized') {
        tp = tp.any();
      }
      if (predName === 'notGeneralized' || predName === 'notAnyGeneralized') {
        tp = tp.negate();
      }
      return tp;
    }
    return new TemporalP(temporal[predName], args.length === 1 ? args[0] : args);
  }

  static intersects(value) {
    return new TemporalP(temporal.intersects, value);
  }

  static disjoint(value) {
    return new TemporalP(temporal.disjoint, value);
  }

  static overlaps(value) {
    return new TemporalP(temporal.overlaps, value);
  }

  static precedes(value) {
    return new TemporalP(temporal.precedes, value);
  }

  static succeeds(value) {
    return new TemporalP(temporal.succeeds, value);
  }

  static overlappedBy(value) {
    return new TemporalP(temporal.overlappedBy, value);
  }

  static meets(value) {
    return new TemporalP(temporal.meets, value);
  }

  static metBy(value) {
    return new TemporalP(temporal.metBy, value);
  }

  static during(value) {
    return new TemporalP(temporal.during, value);
  }

  static contains(value) {
    return new TemporalP(temporal.contains, value);
  }

  static eq(value) {
    return new TemporalP(temporal.eq, value);
  }

  static starts(value) {
    return new TemporalP(temporal.starts, value);
  }

  static startedBy(value) {
    return new TemporalP(temporal.startedBy, value);
  }

  static finishes(value) {
    return new TemporalP(temporal.finishes, value);
  }

  static finishedBy(value) {
    return new TemporalP(temporal.finishedBy, value);
  }

  static between(lower, upper, value) {
    return new TemporalP(temporal.generalized, value, {
      generalizedIntervalRelation: new GeneralizedIntervalRelation(
        GeneralizedRelation.fromAllen(lower),
        GeneralizedRelation.fromAllen(upper),
      ),
    });
  }

  static generalized(value, relation = null) {
    if (relation instanceof GeneralizedRelation) {
      return new TemporalP(temporal.generalized, value, { generalizedRelation: relation });
    }
    if (relation instanceof GeneralizedIntervalRelation) {
      return new TemporalP(temporal.generalized, value, { generalizedIntervalRelation: relation });
    }
    if (relation !== null && relation !== undefined) {
      throw new Error('relation must be GeneralizedRelation or GeneralizedIntervalRelation');
    }
    if (Array.isArray(value) && value.length === 2) {
      const [target, spec] = value;
      if (spec instanceof GeneralizedRelation) {
        return new TemporalP(temporal.generalized, target, { generalizedRelation: spec });
      }
      if (spec instanceof GeneralizedIntervalRelation) {
        return new TemporalP(temporal.generalized, target, { generalizedIntervalRelation: spec });
      }
    }
    return new TemporalP(temporal.generalized, value);
  }

  static anyGeneralized(value, relation = null) {
    return TemporalP.generalized(value, relation).any();
  }

  static notGeneralized(value, relation = null) {
    return TemporalP.generalized(value, relation).negate();
  }

  static notAnyGeneralized(value, relation = null) {
    return TemporalP.generalized(value, relation).any().negate();
  }

  static any(op, value) {
    return new TemporalP(op, value).any();
  }

  static all(op, value) {
    return new TemporalP(op, value).all();
  }

  static not(op, value) {
    return new TemporalP(op, value).negate();
  }

  static notAny(op, value) {
    return new TemporalP(op, value).any().negate();
  }

  toString() {
    const { predicateName, args } = this.toWireParts();
    return `${predicateName}(${args.map((a) => String(a)).join(', ')})`;
  }
}

class TemporalPageRank {
  static get alpha() {
    return '~tinkerpop.temporalPageRank.alpha';
  }

  static get beta() {
    return '~tinkerpop.temporalPageRank.beta';
  }

  static get propertyName() {
    return '~tinkerpop.temporalPageRank.propertyName';
  }

  static get normalize() {
    return '~tinkerpop.temporalPageRank.normalize';
  }

  static get bucketMillis() {
    return '~tinkerpop.temporalPageRank.bucketMillis';
  }
}

class TemporalShortestPath {
  static get target() {
    return 'gremlin.temporalShortestPathVertexProgram.target';
  }

  static get initialTime() {
    return 'gremlin.temporalShortestPathVertexProgram.initialTime';
  }

  static get targetVertexFilter() {
    return 'gremlin.temporalShortestPathVertexProgram.targetVertexFilter';
  }

  static get edgeTraversal() {
    return 'gremlin.temporalShortestPathVertexProgram.edgeTraversal';
  }

  static get maxDistance() {
    return 'gremlin.temporalShortestPathVertexProgram.maxDistance';
  }

  static get includeEdges() {
    return 'gremlin.temporalShortestPathVertexProgram.includeEdges';
  }
}

const temporalOptions = {
  quantifier: '~tinkerpop.temporal.quantifier',
};

module.exports = {
  EnumValue,
  P,
  TextP,
  TemporalP,
  Interval,
  Lifetime,
  GeneralizedRelation,
  GeneralizedIntervalRelation,
  TemporalPageRank,
  TemporalShortestPath,
  temporalOptions,
  withOptions,
  IO,
  Traversal,
  TraversalSideEffects,
  Traverser,
  barrier: toEnum('Barrier', 'normSack'),
  cardinality: toEnum('Cardinality', 'list set single'),
  column: toEnum('Column', 'keys values'),
  direction: toDirectionEnum('Direction', 'BOTH IN OUT from_ to'),
  dt: toEnum('DT', 'second minute hour day'),
  graphSONVersion: toEnum('GraphSONVersion', 'V1_0 V2_0 V3_0'),
  gryoVersion: toEnum('GryoVersion', 'V1_0 V3_0'),
  merge: toEnum('Merge', 'onCreate onMatch outV inV'),
  operator: toEnum('Operator', 'addAll and assign div max min minus mult or sum sumLong'),
  order: toEnum('Order', 'asc desc shuffle'),
  pick: toEnum('Pick', 'any none'),
  pop: toEnum('Pop', 'all first last mixed'),
  scope: toEnum('Scope', 'global local'),
  t: toEnum('T', 'id key label value'),
  lifetimeMode,
  dropMode,
  temporalPathTarget,
  temporal,
  temporalQuantifier,
};
