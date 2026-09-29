package com.sih26036.lmverify.service;

import com.sih26036.lmverify.entity.Instrument;
import com.sih26036.lmverify.entity.InstrumentType;

/**
 * Decides pass/fail for a test reading, so the officer only types readings.
 * The same logic runs on the phone (js/errorcalc.js) for offline feedback; the server
 * always recomputes and never trusts the phone's verdict.
 *
 * OIML_R76 limits are the OIML R76 maximum permissible errors at initial verification
 * (0.5e / 1e / 1.5e by load band and accuracy class). VERIFY against the Legal Metrology
 * (General) Rules 2011 schedule before citing in the PPT.
 */
public final class ErrorCalculator {

    private ErrorCalculator() {
    }

    /** Load band upper limits, in multiples of e, for 0.5e and 1e. Above the second limit: 1.5e. */
    private static double[] bands(String accuracyClass) {
        if (accuracyClass == null) {
            return new double[]{500, 2000};
        }
        return switch (accuracyClass.trim().toUpperCase()) {
            case "I" -> new double[]{50_000, 200_000};
            case "II" -> new double[]{5_000, 20_000};
            case "IIII" -> new double[]{50, 200};
            default -> new double[]{500, 2_000}; // class III, the usual commercial scale
        };
    }

    public static double permissibleError(Instrument instrument, double testLoad) {
        InstrumentType type = instrument.getType();
        double load = Math.abs(testLoad);
        if (type.getErrorModel() == InstrumentType.ErrorModel.PERCENT) {
            double pct = type.getMpePercent() == null ? 0 : type.getMpePercent();
            return round(load * pct / 100.0);
        }
        Double e = instrument.getEValue();
        if (e == null || e <= 0) {
            throw new IllegalArgumentException("Instrument " + instrument.getSerialNo() + " has no verification interval e");
        }
        double m = load / e;
        double[] b = bands(type.getAccuracyClass());
        double multiple = m <= b[0] ? 0.5 : m <= b[1] ? 1.0 : 1.5;
        return round(multiple * e);
    }

    public static double error(double testLoad, double indicatedValue) {
        return round(indicatedValue - testLoad);
    }

    public static boolean withinLimit(double error, double permissible) {
        // Small epsilon avoids floating-point noise failing an exact-limit reading.
        return Math.abs(error) <= permissible + 1e-9;
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }
}
