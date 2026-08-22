/*
 * Copyright 2018 Daniel Underhay & Matthew Daley.
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

import android.support.annotation.LongDef;
import android.support.annotation.Size;

import org.apache.commons.lang3.ArrayUtils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

class Proxmark3CommandNG {

    static final short ACK = 0xff;
    static final short DEBUG_PRINT_STRING = 0x100;
    static final short VERSION = 0x107;
    static final short CAPABILITIES = 0x0112;

    static final short HID_DEMOD_FSK = 0x20b; // CMD_LF_HID_WATCH
    static final short HID_CLONE_TAG = 0x210;
    static final short READER_ISO_14443A = 0x385;

    static final short MEASURE_ANTENNA_TUNING = 0x400;
    static final short MEASURED_ANTENNA_TUNING = 0x410;

    static final short CMD_MEASURE_ANTENNA_TUNING = 0x0400;
    static final short CMD_MEASURE_ANTENNA_TUNING_HF = 0x0401;
    static final short CMD_MEASURE_ANTENNA_TUNING_LF = 0x0402;

    static final short MIFARE_READBL = 0x620;

    static final short MEASURE_ANTENNA_TUNING_FLAG_TUNE_LF = 1;
    static final short MEASURE_ANTENNA_TUNING_FLAG_TUNE_HF = 2;

    static final short ISO14A_CONNECT = 1 << 0;

    static final long COMMANDNG_PREAMBLE_MAGIC = 0x61334d50; // PM3a
    static final short COMMANDNG_POSTAMBLE_MAGIC = 0x3361;     // a3

    // Success (no error)
    static final long PM3_SUCCESS = 0;

    // params
    short cmd = 0;
    short length = 0;
    long magic = 0;
    short crc = 0;
    final long[] oldargs;
    final byte[] data;
    boolean ng = true;

    Proxmark3CommandNG(
                short cmd,
                short length,
                @Size(3) long[] oldargs,
                @Size(max = 512) byte[] data,
                boolean ng
                    ) {
        this.cmd = cmd;
        this.length = length;
        this.magic = COMMANDNG_PREAMBLE_MAGIC;
        this.crc = COMMANDNG_POSTAMBLE_MAGIC;
        this.ng = ng;

        if (oldargs.length != 3) {
            throw new IllegalArgumentException("Invalid number of args");
        }
        this.oldargs = oldargs;

        if (data.length > 512) {
            throw new IllegalArgumentException("Data too long");
        }
        this.data = Arrays.copyOf(data, 512);
    }

    Proxmark3CommandNG(
            short cmd,
            short length,
            @Size(3) long[] oldargs,
            boolean ng) {
        this(cmd, length, oldargs, new byte[0], ng);
    }

    Proxmark3CommandNG(short cmd) {
        this(cmd,(short)0, new long[3], true);
    }

    static int getByteLength() {
        return 8 + 3 * 8 + 512;
    }

    static Proxmark3CommandNG fromBytes(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        short cmd = bb.getShort();

        long[] args = new long[3];
        for (int i = 0; i < 3; ++i) {
            args[i] = bb.getLong();
        }

        byte[] data = new byte[512];
        bb.get(data);

        return new Proxmark3CommandNG(cmd, (short)0, args, data, true);
    }

    byte[] toBytes() {
        ByteBuffer bb = ByteBuffer.allocate(getByteLength());
        bb.order(ByteOrder.LITTLE_ENDIAN);

        bb.putShort(cmd);

        for (long arg : oldargs) {
            bb.putLong(arg);
        }

        bb.put(data);

        byte[] bytes = new byte[bb.capacity()];
        bb.flip();
        bb.get(bytes);

        return bytes;
    }

    @Override
    public String toString() {
        return "<Proxmark3Command " + cmd + ", args " + Arrays.toString(oldargs) + ", data "
                + Arrays.toString(data) + ">";
    }

    public String dataAsString() {
        return new String(ArrayUtils.subarray(data, 0, (int) oldargs[0]));
    }
}
