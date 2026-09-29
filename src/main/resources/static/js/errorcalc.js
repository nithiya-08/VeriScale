// Permissible-error rules, mirrored from ErrorCalculator.java so the officer sees pass/fail
// instantly even offline. The server always recomputes; this is feedback only.
'use strict';

const ErrorCalc = {
  bands(accuracyClass) {
    switch ((accuracyClass || '').trim().toUpperCase()) {
      case 'I': return [50000, 200000];
      case 'II': return [5000, 20000];
      case 'IIII': return [50, 200];
      default: return [500, 2000]; // class III
    }
  },

  round(v) { return Math.round(v * 1e6) / 1e6; },

  permissible(instrument, testLoad) {
    const type = instrument.type;
    const load = Math.abs(testLoad);
    if (type.errorModel === 'PERCENT') {
      return ErrorCalc.round(load * (type.mpePercent || 0) / 100);
    }
    const e = instrument.eValue;
    if (!e || e <= 0) return NaN;
    const m = load / e;
    const [b1, b2] = ErrorCalc.bands(type.accuracyClass);
    const multiple = m <= b1 ? 0.5 : m <= b2 ? 1.0 : 1.5;
    return ErrorCalc.round(multiple * e);
  },

  check(instrument, testLoad, indicated) {
    const error = ErrorCalc.round(indicated - testLoad);
    const permissible = ErrorCalc.permissible(instrument, testLoad);
    return { error, permissible, within: Math.abs(error) <= permissible + 1e-9 };
  },

  /** Suggested test loads: about min, 25%, 50% and max capacity (weighing), or standard volumes. */
  suggestedLoads(instrument) {
    if (instrument.type.errorModel === 'PERCENT') {
      return instrument.type.unit === 'L' ? [5, 20] : [1, 10, 100];
    }
    const max = instrument.capacityMax || 10;
    const min = instrument.capacityMin || (instrument.eValue ? 20 * instrument.eValue : max / 100);
    return [min, max / 4, max / 2, max].map(v => ErrorCalc.round(v));
  },

  describe(instrument) {
    const type = instrument.type;
    // t() comes from i18n.js or the api.js fallback; both fill {placeholders}.
    if (type.errorModel === 'PERCENT') return t('Max permissible error: ±{p}% of test quantity', { p: type.mpePercent });
    return t('Class {c}, e = {e} {unit}: limit 0.5e / 1e / 1.5e by load', { c: type.accuracyClass || 'III', e: instrument.eValue, unit: type.unit || '' });
  },
};
