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

/**
 * The payload for {@link Proxmark3CommandNG#HF_MIFARE_READBL}: {@code mf_readblock_t} in
 * {@code include/pm3_cmd.h}.
 *
 * <pre>
 *     uint8 blockno
 *     uint8 keytype   0 for key A, 1 for key B
 *     uint8 key[6]
 * </pre>
 */
final class Pm3MfReadBlock {

    static final int SIZE = 8;

    static final int KEY_SIZE = 6;

    static final byte KEY_TYPE_A = 0;
    static final byte KEY_TYPE_B = 1;

    private Pm3MfReadBlock() {
    }

    static byte[] toBytes(int blockNumber, byte keyType, byte[] key) {
        if (key.length < KEY_SIZE) {
            throw new IllegalArgumentException("Key too short");
        }

        return Pm3Structs.writer(SIZE)
                .put((byte) blockNumber)
                .put(keyType)
                .put(key, 0, KEY_SIZE)
                .array();
    }
}
