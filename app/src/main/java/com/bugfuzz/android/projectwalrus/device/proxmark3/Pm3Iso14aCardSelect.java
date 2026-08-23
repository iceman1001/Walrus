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

import org.apache.commons.lang3.ArrayUtils;

import java.math.BigInteger;
import java.nio.ByteBuffer;

/**
 * What {@code ReaderIso14443a()} hands back for a selected card: {@code iso14a_card_select_t} in
 * {@code include/mifare.h}.
 *
 * <pre>
 *     uint8 uid[10]
 *     uint8 uidlen     how much of uid[] is real, 4, 7 or 10
 *     uint8 atqa[2]
 *     uint8 sak
 *     uint8 ats_len
 *     uint8 ats[256]
 * </pre>
 *
 * <p>Only the fixed part is required to be present: the firmware sends as much of {@code ats[]} as
 * the card actually returned rather than the full 256 bytes.
 */
final class Pm3Iso14aCardSelect {

    static final int UID_SIZE = 10;

    /** uid[10] + uidlen + atqa[2] + sak + ats_len. */
    static final int HEADER_SIZE = 15;

    private final BigInteger uid;
    private final short atqa;
    private final byte sak;
    private final byte[] ats;

    private Pm3Iso14aCardSelect(BigInteger uid, short atqa, byte sak, byte[] ats) {
        this.uid = uid;
        this.atqa = atqa;
        this.sak = sak;
        this.ats = ats;
    }

    @Nullable
    static Pm3Iso14aCardSelect parse(byte[] data) {
        ByteBuffer bb = Pm3Structs.reader(data, HEADER_SIZE);
        if (bb == null) {
            return null;
        }

        byte[] uidBytes = new byte[UID_SIZE];
        bb.get(uidBytes);

        int uidLength = Math.max(0, Math.min(bb.get() & 0xff, UID_SIZE));
        BigInteger uid = new BigInteger(ArrayUtils.subarray(uidBytes, 0, uidLength));

        short atqa = bb.getShort();
        byte sak = bb.get();

        int atsLength = Math.min(bb.get() & 0xff, data.length - HEADER_SIZE);
        byte[] ats = new byte[Math.max(0, atsLength)];
        bb.get(ats);

        return new Pm3Iso14aCardSelect(uid, atqa, sak, ats);
    }

    BigInteger getUid() {
        return uid;
    }

    short getAtqa() {
        return atqa;
    }

    byte getSak() {
        return sak;
    }

    byte[] getAts() {
        return ats;
    }
}
