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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a connected Proxmark3 or Proxmark5 can actually do: the {@code capabilities_t} struct from
 * {@code include/pm3_cmd.h}, as returned by {@link Proxmark3CommandNG#CAPABILITIES}.
 *
 * <p>This is the single place the board identifies itself. Notably it is <em>not</em> possible to
 * tell a PM3 from a PM5 at USB enumeration time: both currently ship the same VID/PID, so
 * {@link #isPm5()} here is the only signal available.
 *
 * <p>The wire layout is {@code __attribute__((packed))}, little endian, 13 bytes:
 *
 * <pre>
 *     offset 0, 1 byte    version, must equal {@link #CAPABILITIES_VERSION}
 *     offset 1, 4 bytes   baudrate
 *     offset 5, 4 bytes   bigbuf_size
 *     offset 9, 4 bytes   30 one-bit flags, in declaration order
 * </pre>
 *
 * <p>The flags are {@code bool : 1} bitfields inside a packed struct, so GCC on ARM EABI allocates
 * them a byte at a time, filling each byte from its least significant bit upwards. That is what
 * {@link #flag(int)} assumes.
 *
 * <p><b>This bit ordering has not yet been checked against real hardware.</b> It follows from the
 * ABI rather than from a capture, so {@link #getRawFlagsHex()} keeps the four flag bytes around:
 * read them off a board whose configuration is already known (an RDV4 must report
 * {@link #isRdv4()}, any working board must report {@link #hasLf()}) and the ordering is
 * confirmed. See PM3-PM5-DESIGN.md section 5.
 *
 * <p>Note that {@code is_pm5} is set at firmware compile time ({@code #ifdef PM5} in
 * {@code armsrc/appmain.c}), not probed at runtime. That is trustworthy anyway, because a PM5 is
 * an AT32F435 Cortex-M4 and a PM3 an AT91SAM7S ARM7: neither board can run the other's firmware.
 */
final class Pm3Capabilities {

    /** {@code CAPABILITIES_VERSION} in {@code include/pm3_cmd.h}. */
    static final int CAPABILITIES_VERSION = 8;

    /** {@code sizeof(capabilities_t)}: 1 + 4 + 4 header bytes, then 30 bits of flags. */
    static final int STRUCT_SIZE = 13;

    private static final int FLAGS_OFFSET = 9;

    // Flag bit indices, in capabilities_t declaration order.
    private static final int VIA_FPC = 0;
    private static final int VIA_USB = 1;
    private static final int COMPILED_WITH_FLASH = 2;
    private static final int COMPILED_WITH_SMARTCARD = 3;
    private static final int COMPILED_WITH_FPC_USART = 4;
    private static final int COMPILED_WITH_FPC_USART_DEV = 5;
    private static final int COMPILED_WITH_FPC_USART_HOST = 6;
    private static final int COMPILED_WITH_LF = 7;
    private static final int COMPILED_WITH_HITAG = 8;
    private static final int COMPILED_WITH_EM4X50 = 9;
    private static final int COMPILED_WITH_EM4X70 = 10;
    private static final int COMPILED_WITH_ZX8211 = 11;
    private static final int COMPILED_WITH_HFSNIFF = 12;
    private static final int COMPILED_WITH_HFPLOT = 13;
    private static final int COMPILED_WITH_ISO14443A = 14;
    private static final int COMPILED_WITH_ISO14443B = 15;
    private static final int COMPILED_WITH_ISO15693 = 16;
    private static final int COMPILED_WITH_FELICA = 17;
    private static final int COMPILED_WITH_LEGICRF = 18;
    private static final int COMPILED_WITH_ICLASS = 19;
    private static final int COMPILED_WITH_SEOS = 20;
    private static final int COMPILED_WITH_NFCBARCODE = 21;
    private static final int COMPILED_WITH_LCD = 22;
    private static final int HW_AVAILABLE_FLASH = 23;
    private static final int HW_AVAILABLE_SMARTCARD = 24;
    private static final int IS_RDV4 = 25;
    private static final int HW_AVAILABLE_FPGA_FLASH = 26;
    private static final int HW_AVAILABLE_I2C_EEPROM = 27;
    private static final int IS_PM5 = 28;
    private static final int IS_PM5_STD_ANT = 29;

    private static final String[] FLAG_NAMES = {
            "via_fpc", "via_usb", "compiled_with_flash", "compiled_with_smartcard",
            "compiled_with_fpc_usart", "compiled_with_fpc_usart_dev",
            "compiled_with_fpc_usart_host", "compiled_with_lf", "compiled_with_hitag",
            "compiled_with_em4x50", "compiled_with_em4x70", "compiled_with_zx8211",
            "compiled_with_hfsniff", "compiled_with_hfplot", "compiled_with_iso14443a",
            "compiled_with_iso14443b", "compiled_with_iso15693", "compiled_with_felica",
            "compiled_with_legicrf", "compiled_with_iclass", "compiled_with_seos",
            "compiled_with_nfcbarcode", "compiled_with_lcd", "hw_available_flash",
            "hw_available_smartcard", "is_rdv4", "hw_available_fpga_flash",
            "hw_available_i2c_eeprom", "is_pm5", "is_pm5_std_ant"
    };

    private final boolean known;
    private final int version;
    private final long baudRate;
    private final long bigBufSize;
    private final int flags;

    private Pm3Capabilities(boolean known, int version, long baudRate, long bigBufSize,
            int flags) {
        this.known = known;
        this.version = version;
        this.baudRate = baudRate;
        this.bigBufSize = bigBufSize;
        this.flags = flags;
    }

    /**
     * Parses a {@link Proxmark3CommandNG#CAPABILITIES} response payload, or returns null if it is
     * not a struct this build understands. Callers should fall back to {@link #baseline()} rather
     * than refusing the device.
     */
    @Nullable
    static Pm3Capabilities parse(byte[] data) {
        // The desktop client also requires an exact length match (client/src/comms.c), but only
        // because it memcpy()s straight into its own struct. Accepting a longer payload lets this
        // keep working if the firmware ever appends fields without bumping the version.
        ByteBuffer bb = Pm3Structs.reader(data, STRUCT_SIZE);
        if (bb == null) {
            return null;
        }

        int version = bb.get() & 0xff;
        if (version != CAPABILITIES_VERSION) {
            return null;
        }

        long baudRate = Pm3Structs.unsigned(bb.getInt());
        long bigBufSize = Pm3Structs.unsigned(bb.getInt());
        int flags = bb.getInt();

        return new Pm3Capabilities(true, version, baudRate, bigBufSize, flags);
    }

    /**
     * What to assume when the device did not answer, or answered with a struct version this build
     * does not know.
     *
     * <p>The desktop client refuses the connection outright in that situation and tells the user
     * to reflash. That is the wrong call on a phone, where the user may be in the field with no
     * way to reflash, so instead assume the two things every Proxmark3 has ever been able to do —
     * LF and ISO14443A — and claim nothing else.
     */
    static Pm3Capabilities baseline() {
        return new Pm3Capabilities(false, 0, 0, 0,
                (1 << COMPILED_WITH_LF) | (1 << COMPILED_WITH_ISO14443A));
    }

    private boolean flag(int bit) {
        return (flags & (1 << bit)) != 0;
    }

    /** False when these are {@link #baseline()} assumptions rather than the device's own answer. */
    boolean isKnown() {
        return known;
    }

    int getVersion() {
        return version;
    }

    long getBaudRate() {
        return baudRate;
    }

    /** How much sample memory the device has, which bounds a bulk transfer. */
    long getBigBufSize() {
        return bigBufSize;
    }

    boolean isPm5() {
        return flag(IS_PM5);
    }

    /** A PM5 fitted with the standard antenna rather than a dual-frequency one. */
    boolean isPm5StdAnt() {
        return flag(IS_PM5_STD_ANT);
    }

    boolean isRdv4() {
        return flag(IS_RDV4);
    }

    /** Whether LF is compiled in, which gates every HID and Indala operation. */
    boolean hasLf() {
        return flag(COMPILED_WITH_LF);
    }

    /** Whether ISO14443A is compiled in, which gates the MIFARE operations. */
    boolean hasIso14443a() {
        return flag(COMPILED_WITH_ISO14443A);
    }

    boolean hasIso14443b() {
        return flag(COMPILED_WITH_ISO14443B);
    }

    boolean hasIso15693() {
        return flag(COMPILED_WITH_ISO15693);
    }

    boolean hasHitag() {
        return flag(COMPILED_WITH_HITAG);
    }

    boolean hasIclass() {
        return flag(COMPILED_WITH_ICLASS);
    }

    boolean hasFlashStorage() {
        return flag(HW_AVAILABLE_FLASH);
    }

    boolean hasSmartcardSlot() {
        return flag(HW_AVAILABLE_SMARTCARD);
    }

    /** A short board name for display: what the user would call the thing they plugged in. */
    String getBoardName() {
        if (!known) {
            return "Proxmark3";
        }
        if (isPm5()) {
            return isPm5StdAnt() ? "Proxmark5" : "Proxmark5 (dual-frequency antenna)";
        }
        if (isRdv4()) {
            return "Proxmark3 RDV4";
        }

        return "Proxmark3";
    }

    /** The four flag bytes as they arrived, for confirming the bit ordering against hardware. */
    String getRawFlagsHex() {
        return String.format(Locale.US, "%08x", flags);
    }

    @Override
    public String toString() {
        if (!known) {
            return "<Pm3Capabilities unknown, assuming LF + ISO14443A>";
        }

        List<String> set = new ArrayList<>();
        for (int i = 0; i < FLAG_NAMES.length; ++i) {
            if (flag(i)) {
                set.add(FLAG_NAMES[i]);
            }
        }

        return "<Pm3Capabilities " + getBoardName() + ", struct v" + version
                + ", baud " + baudRate + ", bigbuf " + bigBufSize
                + ", flags 0x" + getRawFlagsHex() + " " + set + ">";
    }
}
