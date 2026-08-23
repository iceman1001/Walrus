/*
 * Copyright 2026 Iceman
 *
 * This file is part of Walrus.
 *
 * Walrus is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Walrus is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Walrus.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.bugfuzz.android.projectwalrus.device.proxmark3;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

/**
 * The reply to {@link Proxmark3CommandNG#MEASURE_ANTENNA_TUNING}: {@code struct p} in
 * {@code MeasureAntennaTuning()}, {@code armsrc/appmain.c}. All voltages are in millivolts.
 *
 * <pre>
 *     uint32 v_lf134     LF at 134.83 kHz
 *     uint32 v_lf125     LF at 125 kHz
 *     uint32 v_lfconf    LF at whatever divisor is configured
 *     uint32 v_hf        HF at 13.56 MHz
 *     uint32 peak_v      the highest voltage seen during the LF sweep
 *     uint32 peak_f      the divisor that peak was at, despite the name
 *     int32  divisor     the sampling config divisor
 *     uint8  results[256] one sample per divisor, scaled down to fit a byte
 * </pre>
 */
final class Pm3AntennaTuning {

    static final int SWEEP_SAMPLES = 256;
    static final int SIZE = 28 + SWEEP_SAMPLES;

    /**
     * The firmware graphs the sweep as bytes, doing {@code adcval >> 9} on the way out
     * (see {@code MeasureAntennaTuning()}), so undoing that gets back to millivolts.
     */
    private static final int SWEEP_SAMPLE_SHIFT = 9;

    private final long vLf134;
    private final long vLf125;
    private final long vLfConf;
    private final long vHf;
    private final long peakV;
    private final long peakDivisor;
    private final int divisor;
    private final long[] sweep;

    private Pm3AntennaTuning(long vLf134, long vLf125, long vLfConf, long vHf, long peakV,
            long peakDivisor, int divisor, long[] sweep) {
        this.vLf134 = vLf134;
        this.vLf125 = vLf125;
        this.vLfConf = vLfConf;
        this.vHf = vHf;
        this.peakV = peakV;
        this.peakDivisor = peakDivisor;
        this.divisor = divisor;
        this.sweep = sweep;
    }

    @Nullable
    static Pm3AntennaTuning parse(byte[] data) {
        ByteBuffer bb = Pm3Structs.reader(data, SIZE);
        if (bb == null) {
            return null;
        }

        long vLf134 = Pm3Structs.unsigned(bb.getInt());
        long vLf125 = Pm3Structs.unsigned(bb.getInt());
        long vLfConf = Pm3Structs.unsigned(bb.getInt());
        long vHf = Pm3Structs.unsigned(bb.getInt());
        long peakV = Pm3Structs.unsigned(bb.getInt());
        long peakDivisor = Pm3Structs.unsigned(bb.getInt());
        int divisor = bb.getInt();

        long[] sweep = new long[SWEEP_SAMPLES];
        for (int i = 0; i < SWEEP_SAMPLES; ++i) {
            sweep[i] = (long) (bb.get() & 0xff) << SWEEP_SAMPLE_SHIFT;
        }

        return new Pm3AntennaTuning(vLf134, vLf125, vLfConf, vHf, peakV, peakDivisor, divisor,
                sweep);
    }

    /** LF_DIV2FREQ() from pm3_cmd.h: the frequency a divisor selects, in Hz. */
    static float divisorToFrequency(long divisor) {
        return 12e6f / (divisor + 1);
    }

    long getVoltageLf134() {
        return vLf134;
    }

    long getVoltageLf125() {
        return vLf125;
    }

    long getVoltageLfConfigured() {
        return vLfConf;
    }

    long getVoltageHf() {
        return vHf;
    }

    long getPeakVoltage() {
        return peakV;
    }

    long getPeakDivisor() {
        return peakDivisor;
    }

    float getPeakFrequency() {
        return divisorToFrequency(peakDivisor);
    }

    int getSamplingDivisor() {
        return divisor;
    }

    /** The LF sweep, one millivolt reading per divisor. */
    long[] getSweepMillivolts() {
        return sweep;
    }
}
