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
 * The payload for the continuous measurements, {@link Proxmark3CommandNG#MEASURE_ANTENNA_TUNING_LF}
 * and {@link Proxmark3CommandNG#MEASURE_ANTENNA_TUNING_HF}.
 *
 * <p>There is no named C struct for this one: the firmware reads the bytes straight out of
 * {@code packet->data.asBytes} and is strict about the length, rejecting an LF payload that is not
 * exactly two bytes and an HF one that is not exactly one (see {@code armsrc/appmain.c}).
 *
 * <pre>
 *     LF: uint8 mode, uint8 divisor
 *     HF: uint8 mode
 * </pre>
 */
final class Pm3TuneMode {

    private Pm3TuneMode() {
    }

    static byte[] toBytes(boolean lf, int mode) {
        return lf
                ? new byte[]{(byte) mode, (byte) Proxmark3CommandNG.LF_DIVISOR_125}
                : new byte[]{(byte) mode};
    }

    /**
     * One millivolt reading from a {@link Proxmark3CommandNG#TUNE_MODE_READ}, or null if the
     * payload is not a width this understands.
     *
     * <p>Older firmware answers HF with a uint16 and current firmware with a uint32, which matters
     * because a tuned antenna can read above the 65.535V a uint16 tops out at. The client takes
     * either, so this does too.
     */
    @Nullable
    static Long parseVoltage(byte[] data) {
        if (data == null) {
            return null;
        }

        ByteBuffer bb = Pm3Structs.reader(data, data.length);
        if (bb == null) {
            return null;
        }

        switch (data.length) {
            case 4:
                return Pm3Structs.unsigned(bb.getInt());
            case 2:
                return (long) (bb.getShort() & 0xffff);
            default:
                return null;
        }
    }
}
