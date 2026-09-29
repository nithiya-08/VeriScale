package com.sih26036.lmverify.service;

import com.sih26036.lmverify.entity.Instrument;
import com.sih26036.lmverify.entity.InstrumentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErrorCalculatorTest {

    private static Instrument weighing(String accuracyClass, double e) {
        InstrumentType t = InstrumentType.builder().name("Scale").errorModel(InstrumentType.ErrorModel.OIML_R76)
                .accuracyClass(accuracyClass).validityMonths(12).fee(0).build();
        return Instrument.builder().serialNo("T-1").type(t).eValue(e).build();
    }

    private static Instrument percent(double mpe) {
        InstrumentType t = InstrumentType.builder().name("Fuel").errorModel(InstrumentType.ErrorModel.PERCENT)
                .mpePercent(mpe).validityMonths(24).fee(0).build();
        return Instrument.builder().serialNo("T-2").type(t).build();
    }

    /** Class III, e = 5 g: bands end at 500e (2.5 kg) and 2000e (10 kg). */
    @ParameterizedTest(name = "class III load {0} kg -> ±{1} kg")
    @CsvSource({
            "0.1,   0.0025",
            "2.5,   0.0025",   // exactly 500e is still the 0.5e band
            "2.505, 0.005",
            "10,    0.005",    // exactly 2000e
            "10.5,  0.0075",
            "30,    0.0075",
    })
    void classThreeBands(double load, double expected) {
        assertThat(ErrorCalculator.permissibleError(weighing("III", 0.005), load)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "class {0}: {1} e -> {2} e")
    @CsvSource({
            "I,    50000,  0.5", "I,    50001,  1.0", "I,    200001, 1.5",
            "II,   5000,   0.5", "II,   5001,   1.0", "II,   20001,  1.5",
            "IIII, 50,     0.5", "IIII, 51,     1.0", "IIII, 201,    1.5",
    })
    void bandsPerAccuracyClass(String cls, double multiplesOfE, double expectedMultiple) {
        assertThat(ErrorCalculator.permissibleError(weighing(cls, 1.0), multiplesOfE)).isEqualTo(expectedMultiple);
    }

    @Test
    void percentModelScalesWithQuantity() {
        assertThat(ErrorCalculator.permissibleError(percent(0.5), 20)).isEqualTo(0.1);
        assertThat(ErrorCalculator.permissibleError(percent(0.5), 5)).isEqualTo(0.025);
    }

    @Test
    void readingExactlyAtTheLimitPasses() {
        double err = ErrorCalculator.error(10, 10.005);
        assertThat(ErrorCalculator.withinLimit(err, 0.005)).isTrue();
        assertThat(ErrorCalculator.withinLimit(ErrorCalculator.error(10, 10.0051), 0.005)).isFalse();
    }

    @Test
    void negativeErrorsAreJudgedByMagnitude() {
        assertThat(ErrorCalculator.error(30, 29.99)).isEqualTo(-0.01);
        assertThat(ErrorCalculator.withinLimit(-0.0075, 0.0075)).isTrue();
        assertThat(ErrorCalculator.withinLimit(-0.008, 0.0075)).isFalse();
    }

    @Test
    void weighingInstrumentWithoutIntervalIsRejected() {
        assertThatThrownBy(() -> ErrorCalculator.permissibleError(weighing("III", 0), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
