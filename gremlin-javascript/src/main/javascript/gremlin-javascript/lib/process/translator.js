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

const traversalModule = require('./traversal');
const { Traversal, P, Lifetime, Interval, GeneralizedRelation, GeneralizedIntervalRelation, TemporalP, EnumValue } =
  traversalModule;
const Bytecode = require('./bytecode');

/**
 * Class to translate glv bytecode steps into executable Gremlin-Groovy script
 */
class Translator {
  constructor(traversalSource) {
    this._traversalSource = traversalSource;
  }

  getTraversalSource() {
    return this._traversalSource;
  }

  getTargetLanguage() {
    return 'gremlin-groovy';
  }

  of(traversalSource) {
    this._traversalSource = traversalSource;
  }

  /**
   * Returns a script representation of the given bytecode instructions.
   * @param {Object} bytecodeOrTraversal The traversal or bytecode of a traversal containing step instructions.
   * @param {boolean} child Determines if a traversal object should be treated as an anonymous child or if it is a spawn from "g"
   * @returns {string} Gremlin-Groovy script
   */
  translate(bytecodeOrTraversal, child = false) {
    let script = child ? '__' : this._traversalSource;
    const bc = bytecodeOrTraversal instanceof Bytecode ? bytecodeOrTraversal : bytecodeOrTraversal.getBytecode();

    const instructions = bc.stepInstructions;

    // build the script from the glv instructions.
    for (let i = 0; i < instructions.length; i++) {
      const params = instructions[i].slice(1);
      script += '.' + instructions[i][0] + '(';

      if (params.length) {
        for (let k = 0; k < params.length; k++) {
          if (k > 0) {
            script += ', ';
          }

          script += this.convert(params[k]);
        }
      }

      script += ')';
    }

    return script;
  }

  /**
   * Converts an object to a Gremlin script representation.
   * @param {Object} anyObject The object to convert to a script representation
   * @returns {string} The Gremlin script representation
   */
  convert(anyObject) {
    let script = '';
    if (Object(anyObject) === anyObject) {
      if (anyObject instanceof Traversal) {
        script += this.translate(anyObject.getBytecode(), true);
      } else if (anyObject instanceof TemporalP) {
        let tpScript = `TemporalP.${anyObject.biPredicate.elementName}(${this.convert(anyObject.value)}`;
        if (anyObject.generalizedRelation) {
          tpScript += `, ${this.convert(anyObject.generalizedRelation)}`;
        } else if (anyObject.generalizedIntervalRelation) {
          tpScript += `, ${this.convert(anyObject.generalizedIntervalRelation)}`;
        }
        tpScript += ')';
        if (anyObject.quantifier && anyObject.quantifier.elementName === 'ANY') {
          tpScript += '.any()';
        }
        if (anyObject.negated) {
          tpScript += '.negate()';
        }
        script += tpScript;
      } else if (anyObject instanceof P) {
        if (anyObject.operator === 'and' || anyObject.operator === 'or') {
          script += `${this.convert(anyObject.value)}.${anyObject.operator}(${this.convert(anyObject.other)})`;
        } else if (anyObject.other !== undefined && anyObject.other !== null) {
          script += `${anyObject.operator}(${this.convert(anyObject.value)}, ${this.convert(anyObject.other)})`;
        } else {
          script += `${anyObject.operator}(${this.convert(anyObject.value)})`;
        }
      } else if (anyObject instanceof Lifetime) {
        if (anyObject.isEmpty()) {
          script += 'Lifetime.empty()';
        } else if (anyObject.isUnbounded()) {
          script += 'Lifetime.UNBOUNDED';
        } else {
          script += `Lifetime.fromTemporalIntervals('${anyObject.toTemporalIntervalsString()}')`;
        }
      } else if (anyObject instanceof Interval) {
        script += `new Lifetime.Interval(${anyObject.startMillis}L, ${anyObject.endMillis}L)`;
      } else if (anyObject instanceof GeneralizedRelation) {
        script += `new GeneralizedRelation(${anyObject.p}, ${anyObject.q}, ${anyObject.zones.join(', ')})`;
      } else if (anyObject instanceof GeneralizedIntervalRelation) {
        script += anyObject.isEmpty()
          ? `GeneralizedIntervalRelation.empty(${anyObject.p}, ${anyObject.q})`
          : `new GeneralizedIntervalRelation(${this.convert(anyObject.lower)}, ${this.convert(anyObject.upper)})`;
      } else if (
        anyObject instanceof EnumValue &&
        (anyObject.typeName === 'LifetimeMode' ||
          anyObject.typeName === 'DropMode' ||
          anyObject.typeName === 'TemporalPathTarget' ||
          anyObject.typeName === 'Temporal' ||
          anyObject.typeName === 'Quantifier')
      ) {
        const prefix = anyObject.typeName === 'Quantifier' ? 'Temporal.Quantifier' : anyObject.typeName;
        script += `${prefix}.${anyObject.elementName}`;
      } else if (anyObject.toString() === '[object Object]') {
        Object.keys(anyObject).forEach(function (key, index) {
          if (index > 0) {
            script += ', ';
          }
          script += `('${key}', `;
          if (anyObject[key] instanceof String || typeof anyObject[key] === 'string') {
            script += `'${anyObject[key]}'`;
          } else {
            script += anyObject[key];
          }
          script += ')';
        });
      } else if (Array.isArray(anyObject)) {
        const parts = [];
        for (const item of anyObject) {
          parts.push(this.convert(item));
        }
        script += '[' + parts.join(', ') + ']';
      } else {
        script += anyObject.toString();
      }
    } else if (anyObject === undefined || anyObject === null) {
      script += 'null';
    } else if (typeof anyObject === 'bigint') {
      script += `${anyObject}L`;
    } else if (typeof anyObject === 'number' || typeof anyObject === 'boolean') {
      script += anyObject;
    } else {
      script += `'${`${anyObject}`.replace("'", "\\'")}'`; // eslint-disable-line quotes
    }

    return script;
  }
}

module.exports = Translator;
