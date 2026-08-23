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

import com.bugfuzz.android.projectwalrus.util.MiscUtils;

import org.apache.commons.lang3.ArrayUtils;

import java.nio.ByteBuffer;

/**
 * The reply to {@link Proxmark3CommandNG#VERSION}: {@code struct p} in {@code SendVersion()},
 * {@code armsrc/appmain.c}.
 *
 * <pre>
 *     uint32 id              the ARM chip id
 *     uint32 section_size
 *     uint32 versionstr_len  including the terminating NUL
 *     char   versionstr[versionstr_len]
 * </pre>
 */
final class Pm3VersionInfo {

    /** Everything up to the start of the string. */
    static final int HEADER_SIZE = 12;

    private final long chipId;
    private final long sectionSize;
    private final String versionString;

    private Pm3VersionInfo(long chipId, long sectionSize, String versionString) {
        this.chipId = chipId;
        this.sectionSize = sectionSize;
        this.versionString = versionString;
    }

    @Nullable
    static Pm3VersionInfo parse(byte[] data) {
        ByteBuffer bb = Pm3Structs.reader(data, HEADER_SIZE);
        if (bb == null) {
            return null;
        }

        long chipId = Pm3Structs.unsigned(bb.getInt());
        long sectionSize = Pm3Structs.unsigned(bb.getInt());
        int versionStringLength = bb.getInt();

        // Trust the payload over the claimed length: firmware that overstates it should not make
        // this read off the end, and the NUL is not wanted here.
        versionStringLength = Math.max(0,
                Math.min(versionStringLength - 1, data.length - HEADER_SIZE));

        return new Pm3VersionInfo(chipId, sectionSize, MiscUtils.stripAnsi(new String(
                ArrayUtils.subarray(data, HEADER_SIZE, HEADER_SIZE + versionStringLength))));
    }

    long getChipId() {
        return chipId;
    }

    long getSectionSize() {
        return sectionSize;
    }

    /** The multi-line banner the client prints for {@code hw version}, colour escapes removed. */
    String getVersionString() {
        return versionString;
    }
}
