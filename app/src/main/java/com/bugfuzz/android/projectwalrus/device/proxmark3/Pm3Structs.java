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
import java.nio.ByteOrder;

/**
 * Shared plumbing for the payload structs an NG frame carries.
 *
 * <p>Every one of these mirrors a C struct that the firmware or the client declares, and each one
 * says where. They are all {@code __attribute__((packed))} and little endian, so there is never
 * any padding to skip: the Java field order is the C field order, and a size mismatch means the
 * two have drifted apart.
 *
 * <p>Keeping them as named types rather than open-coded {@link ByteBuffer} arithmetic at the call
 * site is the whole point. When a struct gains a field upstream, there is one place to change and
 * one test that fails, instead of an offset buried in a device method.
 */
final class Pm3Structs {

    private Pm3Structs() {
    }

    /** A little-endian reader over a payload, or null if it is too short to hold {@code size}. */
    @Nullable
    static ByteBuffer reader(@Nullable byte[] data, int size) {
        if (data == null || data.length < size) {
            return null;
        }

        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        return bb;
    }

    /** A little-endian writer for a payload of exactly {@code size} bytes. */
    static ByteBuffer writer(int size) {
        ByteBuffer bb = ByteBuffer.allocate(size);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        return bb;
    }

    /** Widens a uint32 that Java has read into a sign-extended int. */
    static long unsigned(int value) {
        return value & 0xffffffffL;
    }
}
