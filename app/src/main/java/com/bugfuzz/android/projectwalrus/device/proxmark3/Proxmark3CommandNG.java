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

import androidx.annotation.IntDef;

import com.bugfuzz.android.projectwalrus.util.MiscUtils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * One Proxmark3 "NG" frame, in either direction.
 *
 * <p>Wire format, all little endian, from {@code include/pm3_cmd.h}:
 *
 * <pre>
 * command (host -&gt; device), 8 + length + 2 bytes:
 *     uint32 magic         COMMANDNG_PREAMBLE_MAGIC ("PM3a")
 *     uint16 length:15     length of the payload
 *            ng:1          payload is a bare NG payload, not a MIX one
 *     uint16 cmd
 *     uint8  payload[length]
 *     uint16 crc           COMMANDNG_POSTAMBLE_MAGIC ("a3") is accepted in place of a CRC
 *
 * response (device -&gt; host), 10 + length + 2 bytes:
 *     uint32 magic         RESPONSENG_PREAMBLE_MAGIC ("PM3b")
 *     uint16 length:15
 *            ng:1
 *     int8   status        PM3_SUCCESS or a negative PM3_E* code
 *     int8   reason
 *     uint16 cmd
 *     uint8  payload[length]
 *     uint16 crc           RESPONSENG_POSTAMBLE_MAGIC ("b3") when CRCs are off, which is the
 *                          default over USB
 * </pre>
 *
 * <p>A frame is either "NG" ({@code ng} set), where the payload is exactly the command's own
 * struct, or "MIX" ({@code ng} clear), where the payload is three uint64 legacy args followed by
 * the data. MIX is how the firmware's {@code reply_mix()} and the old {@code arg0..arg2} calling
 * convention survive inside NG framing; several opcodes still use it.
 *
 * <p>Note that the firmware still <em>accepts</em> legacy 544-byte {@code PacketCommandOLD}
 * commands, but {@code reply_ng_internal()} in {@code armsrc/cmd.c} is the only thing that ever
 * sends a response, and it always emits NG framing. There is therefore no point in speaking the
 * legacy format: nothing can be read back.
 */
class Proxmark3CommandNG {

    static final int PM3_CMD_DATA_SIZE = 512;

    static final int COMMANDNG_PREAMBLE_MAGIC = 0x61334d50;  // PM3a
    static final short COMMANDNG_POSTAMBLE_MAGIC = 0x3361;   // a3
    static final int RESPONSENG_PREAMBLE_MAGIC = 0x62334d50; // PM3b

    static final int COMMAND_PREAMBLE_SIZE = 8;
    static final int RESPONSE_PREAMBLE_SIZE = 10;
    static final int POSTAMBLE_SIZE = 2;

    static final int MIX_ARGS_SIZE = 3 * 8;

    // Opcodes, from include/pm3_cmd.h.
    static final int NACK = 0x00fe;
    static final int ACK = 0x00ff;
    static final int DEBUG_PRINT_STRING = 0x0100;
    static final int VERSION = 0x0107;
    static final int PING = 0x0109;
    static final int CAPABILITIES = 0x0112;
    /** Waiting Time eXtension: the firmware asking the host to keep waiting. */
    static final int WTX = 0x0116;
    static final int LF_HID_WATCH = 0x020b;
    static final int LF_HID_CLONE = 0x0210;
    static final int HF_ISO14443A_READER = 0x0385;
    static final int MEASURE_ANTENNA_TUNING = 0x0400;
    static final int HF_MIFARE_READBL = 0x0620;
    static final int UNKNOWN = 0xffff;

    // Flags for HF_ISO14443A_READER's first MIX arg (iso14a_command_t).
    static final long ISO14A_CONNECT = 1 << 0;

    // Status codes, from include/pm3_cmd.h.
    static final byte PM3_SUCCESS = 0;
    static final byte PM3_EOPABORTED = -5;

    /**
     * Not annotated with {@link Opcode}: a parsed response can carry any opcode the firmware
     * chooses to send, including ones this app has no constant for.
     */
    final int cmd;
    final boolean ng;
    final byte status;
    final byte reason;
    /** Only meaningful when {@link #ng} is false. */
    final long[] oldargs;
    /** The payload, with the MIX args already stripped when {@link #ng} is false. */
    final byte[] data;

    private Proxmark3CommandNG(int cmd, boolean ng, byte status, byte reason,
            long[] oldargs, byte[] data) {
        if (data.length > PM3_CMD_DATA_SIZE) {
            throw new IllegalArgumentException("Data too long");
        }
        if (oldargs.length != 3) {
            throw new IllegalArgumentException("Invalid number of args");
        }

        this.cmd = cmd;
        this.ng = ng;
        this.status = status;
        this.reason = reason;
        this.oldargs = oldargs;
        this.data = data;
    }

    /** A bare NG command: the payload is the opcode's own struct. */
    static Proxmark3CommandNG ng(@Opcode int cmd, byte[] data) {
        return new Proxmark3CommandNG(cmd, true, PM3_SUCCESS, (byte) 0, new long[3], data);
    }

    /** A bare NG command with no payload. */
    static Proxmark3CommandNG ng(@Opcode int cmd) {
        return ng(cmd, new byte[0]);
    }

    /** A MIX command: three legacy uint64 args, then the data. */
    static Proxmark3CommandNG mix(@Opcode int cmd, long[] oldargs, byte[] data) {
        return new Proxmark3CommandNG(cmd, false, PM3_SUCCESS, (byte) 0, oldargs, data);
    }

    /**
     * Parses one response frame starting at the beginning of {@code in}, or returns null if
     * {@code in} does not hold a whole frame yet. The caller has already checked the magic.
     */
    static Proxmark3CommandNG responseFromBytes(byte[] in) {
        if (in.length < RESPONSE_PREAMBLE_SIZE) {
            return null;
        }

        ByteBuffer bb = ByteBuffer.wrap(in);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        bb.getInt(); // magic, already checked by the caller

        int lengthAndNg = bb.getShort() & 0xffff;
        int length = lengthAndNg & 0x7fff;
        boolean ng = (lengthAndNg & 0x8000) != 0;

        byte status = bb.get();
        byte reason = bb.get();
        int cmd = bb.getShort() & 0xffff;

        if (length > PM3_CMD_DATA_SIZE
                || in.length < RESPONSE_PREAMBLE_SIZE + length + POSTAMBLE_SIZE) {
            return null;
        }

        long[] oldargs = new long[3];
        byte[] data;

        if (ng) {
            data = new byte[length];
            bb.get(data);
        } else {
            if (length < MIX_ARGS_SIZE) {
                return null;
            }

            for (int i = 0; i < 3; ++i) {
                oldargs[i] = bb.getLong();
            }

            data = new byte[length - MIX_ARGS_SIZE];
            bb.get(data);
        }

        return new Proxmark3CommandNG(cmd, ng, status, reason, oldargs, data);
    }

    /** The on-the-wire size of the response frame that {@code responseFromBytes} just parsed. */
    int getResponseByteLength() {
        return RESPONSE_PREAMBLE_SIZE + (ng ? data.length : MIX_ARGS_SIZE + data.length)
                + POSTAMBLE_SIZE;
    }

    /** A placeholder handed to the receive sinks when the stream had to be resynchronised. */
    static Proxmark3CommandNG unknown() {
        return new Proxmark3CommandNG(UNKNOWN, true, PM3_SUCCESS, (byte) 0, new long[3],
                new byte[0]);
    }

    byte[] toBytes() {
        byte[] payload;
        if (ng) {
            payload = data;
        } else {
            ByteBuffer args = ByteBuffer.allocate(MIX_ARGS_SIZE + data.length);
            args.order(ByteOrder.LITTLE_ENDIAN);
            for (long arg : oldargs) {
                args.putLong(arg);
            }
            args.put(data);
            payload = args.array();
        }

        ByteBuffer bb = ByteBuffer.allocate(
                COMMAND_PREAMBLE_SIZE + payload.length + POSTAMBLE_SIZE);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        bb.putInt(COMMANDNG_PREAMBLE_MAGIC);
        bb.putShort((short) ((payload.length & 0x7fff) | (ng ? 0x8000 : 0)));
        bb.putShort((short) cmd);
        bb.put(payload);
        // The firmware accepts the postamble magic in place of a real CRC, and CRCs are off over
        // USB by default (see receive_ng_internal() in armsrc/cmd.c).
        bb.putShort(COMMANDNG_POSTAMBLE_MAGIC);

        return bb.array();
    }

    /**
     * The payload of a {@link #DEBUG_PRINT_STRING} response, which is a uint16 flag field
     * followed by the message, with the firmware's ANSI colour escapes stripped out.
     */
    String debugString() {
        if (data.length < 2) {
            return "";
        }

        return MiscUtils.stripAnsi(
                new String(Arrays.copyOfRange(data, 2, data.length)).trim());
    }

    @Override
    public String toString() {
        return "<Proxmark3CommandNG cmd 0x" + Integer.toHexString(cmd) + ", "
                + (ng ? "NG" : "MIX, args " + Arrays.toString(oldargs))
                + ", status " + status + ", " + data.length + " bytes of data>";
    }

    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            NACK,
            ACK,
            DEBUG_PRINT_STRING,
            VERSION,
            PING,
            CAPABILITIES,
            WTX,
            LF_HID_WATCH,
            LF_HID_CLONE,
            HF_ISO14443A_READER,
            MEASURE_ANTENNA_TUNING,
            HF_MIFARE_READBL,
            UNKNOWN
    })
    public @interface Opcode {
    }
}
