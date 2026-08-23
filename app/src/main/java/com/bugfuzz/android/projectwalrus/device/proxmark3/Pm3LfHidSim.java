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

import java.math.BigInteger;

/**
 * The payload for {@link Proxmark3CommandNG#LF_HID_CLONE}: {@code lf_hidsim_t} in
 * {@code include/pm3_cmd.h}, which the clone command shares with the simulate one.
 *
 * <pre>
 *     uint32 hi2
 *     uint32 hi
 *     uint32 lo
 *     uint8  longFMT   set for formats wider than 44 bits
 *     bool   Q5        the target is a Q5/T5555 rather than a T55x7
 *     bool   EM        the target is an EM4305/EM4469
 * </pre>
 */
final class Pm3LfHidSim {

    static final int SIZE = 15;

    /** Wider than this and the tag needs the long format, per the client's HID handling. */
    static final int LONG_FORMAT_BIT_LENGTH = 44;

    private Pm3LfHidSim() {
    }

    static byte[] toBytes(BigInteger data, boolean q5, boolean em) {
        return Pm3Structs.writer(SIZE)
                .putInt(data.shiftRight(64).intValue())
                .putInt(data.shiftRight(32).intValue())
                .putInt(data.intValue())
                .put((byte) (data.bitLength() > LONG_FORMAT_BIT_LENGTH ? 1 : 0))
                .put((byte) (q5 ? 1 : 0))
                .put((byte) (em ? 1 : 0))
                .array();
    }
}
