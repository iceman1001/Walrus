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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * These build capabilities_t payloads by hand rather than replaying a captured one, because no
 * capture from real hardware exists yet. They therefore pin the parser to the layout documented in
 * {@link Pm3Capabilities}, and will keep passing even if that layout turns out to be wrong.
 *
 * <p>Once a real response has been captured, add it here verbatim as a fixture: that is what
 * actually confirms the bit ordering.
 */
public class Pm3CapabilitiesTest {

    // Bit indices, in capabilities_t declaration order.
    private static final int COMPILED_WITH_LF = 7;
    private static final int COMPILED_WITH_ISO14443A = 14;
    private static final int HW_AVAILABLE_SMARTCARD = 24;
    private static final int IS_RDV4 = 25;
    private static final int IS_PM5 = 28;
    private static final int IS_PM5_STD_ANT = 29;

    private static byte[] payload(int version, long baudRate, long bigBufSize, int... setBits) {
        int flags = 0;
        for (int bit : setBits) {
            flags |= 1 << bit;
        }

        ByteBuffer bb = ByteBuffer.allocate(Pm3Capabilities.STRUCT_SIZE);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) version);
        bb.putInt((int) baudRate);
        bb.putInt((int) bigBufSize);
        bb.putInt(flags);

        return bb.array();
    }

    @Test
    public void parsesHeaderFields() {
        Pm3Capabilities caps = Pm3Capabilities.parse(
                payload(Pm3Capabilities.CAPABILITIES_VERSION, 115200, 40000));

        assertTrue(caps.isKnown());
        assertEquals(Pm3Capabilities.CAPABILITIES_VERSION, caps.getVersion());
        assertEquals(115200, caps.getBaudRate());
        assertEquals(40000, caps.getBigBufSize());
    }

    @Test
    public void readsBigBufSizeAboveTwoGigabytesUnsigned() {
        Pm3Capabilities caps = Pm3Capabilities.parse(
                payload(Pm3Capabilities.CAPABILITIES_VERSION, 0xffffffffL, 0x80000000L));

        assertEquals(0xffffffffL, caps.getBaudRate());
        assertEquals(0x80000000L, caps.getBigBufSize());
    }

    @Test
    public void identifiesAPlainProxmark3() {
        Pm3Capabilities caps = Pm3Capabilities.parse(
                payload(Pm3Capabilities.CAPABILITIES_VERSION, 115200, 40000,
                        COMPILED_WITH_LF, COMPILED_WITH_ISO14443A));

        assertFalse(caps.isPm5());
        assertFalse(caps.isRdv4());
        assertTrue(caps.hasLf());
        assertTrue(caps.hasIso14443a());
        assertEquals("Proxmark3", caps.getBoardName());
    }

    @Test
    public void identifiesAnRdv4() {
        Pm3Capabilities caps = Pm3Capabilities.parse(
                payload(Pm3Capabilities.CAPABILITIES_VERSION, 115200, 40000,
                        COMPILED_WITH_LF, IS_RDV4, HW_AVAILABLE_SMARTCARD));

        assertTrue(caps.isRdv4());
        assertTrue(caps.hasSmartcardSlot());
        assertFalse(caps.isPm5());
        assertEquals("Proxmark3 RDV4", caps.getBoardName());
    }

    @Test
    public void identifiesAProxmark5() {
        Pm3Capabilities caps = Pm3Capabilities.parse(
                payload(Pm3Capabilities.CAPABILITIES_VERSION, 115200, 40000,
                        COMPILED_WITH_LF, IS_PM5, IS_PM5_STD_ANT));

        assertTrue(caps.isPm5());
        assertTrue(caps.isPm5StdAnt());
        assertFalse(caps.isRdv4());
        assertEquals("Proxmark5", caps.getBoardName());
    }

    @Test
    public void namesADualFrequencyProxmark5() {
        Pm3Capabilities caps = Pm3Capabilities.parse(
                payload(Pm3Capabilities.CAPABILITIES_VERSION, 115200, 40000, IS_PM5));

        assertTrue(caps.isPm5());
        assertFalse(caps.isPm5StdAnt());
        assertEquals("Proxmark5 (dual-frequency antenna)", caps.getBoardName());
    }

    @Test
    public void rejectsAnUnknownStructVersion() {
        assertNull(Pm3Capabilities.parse(
                payload(Pm3Capabilities.CAPABILITIES_VERSION + 1, 115200, 40000, IS_PM5)));
    }

    @Test
    public void rejectsATruncatedPayload() {
        byte[] full = payload(Pm3Capabilities.CAPABILITIES_VERSION, 115200, 40000);
        byte[] truncated = new byte[full.length - 1];
        System.arraycopy(full, 0, truncated, 0, truncated.length);

        assertNull(Pm3Capabilities.parse(truncated));
        assertNull(Pm3Capabilities.parse(new byte[0]));
        assertNull(Pm3Capabilities.parse(null));
    }

    @Test
    public void acceptsAPayloadWithExtraTrailingFields() {
        byte[] full = payload(Pm3Capabilities.CAPABILITIES_VERSION, 115200, 40000, IS_PM5);
        byte[] extended = new byte[full.length + 4];
        System.arraycopy(full, 0, extended, 0, full.length);

        Pm3Capabilities caps = Pm3Capabilities.parse(extended);

        assertTrue(caps.isKnown());
        assertTrue(caps.isPm5());
    }

    @Test
    public void baselineAssumesOnlyTheBasics() {
        Pm3Capabilities caps = Pm3Capabilities.baseline();

        assertFalse(caps.isKnown());
        assertTrue(caps.hasLf());
        assertTrue(caps.hasIso14443a());
        assertFalse(caps.isPm5());
        assertFalse(caps.isRdv4());
        assertFalse(caps.hasSmartcardSlot());
        assertEquals("Proxmark3", caps.getBoardName());
    }
}
