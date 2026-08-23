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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Layout checks for the NG payload structs. Each one pins the Java type to the C declaration it
 * names, so that a field added upstream shows up here rather than as a wrong reading on a bench.
 */
public class Pm3StructsTest {

    private static ByteBuffer buffer(int size) {
        ByteBuffer bb = ByteBuffer.allocate(size);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        return bb;
    }

    // ---- Pm3VersionInfo ----

    @Test
    public void parsesAVersionReply() {
        byte[] versionString = "Iceman/master/v4.20469 ".getBytes();
        byte[] data = buffer(Pm3VersionInfo.HEADER_SIZE + versionString.length)
                .putInt(0x270b0a40)
                .putInt(0x00040000)
                .putInt(versionString.length)
                .put(versionString)
                .array();

        Pm3VersionInfo info = Pm3VersionInfo.parse(data);

        assertEquals(0x270b0a40L, info.getChipId());
        assertEquals(0x00040000L, info.getSectionSize());
        assertEquals("Iceman/master/v4.20469", info.getVersionString());
    }

    @Test
    public void doesNotReadPastAVersionStringThatOverstatesItsLength() {
        byte[] versionString = "short".getBytes();
        byte[] data = buffer(Pm3VersionInfo.HEADER_SIZE + versionString.length)
                .putInt(0)
                .putInt(0)
                .putInt(9999)
                .put(versionString)
                .array();

        assertEquals("short", Pm3VersionInfo.parse(data).getVersionString());
    }

    @Test
    public void rejectsATruncatedVersionReply() {
        assertNull(Pm3VersionInfo.parse(new byte[Pm3VersionInfo.HEADER_SIZE - 1]));
    }

    // ---- Pm3AntennaTuning ----

    @Test
    public void parsesAnAntennaTuningReply() {
        ByteBuffer bb = buffer(Pm3AntennaTuning.SIZE);
        bb.putInt(21000);   // v_lf134
        bb.putInt(24000);   // v_lf125
        bb.putInt(23000);   // v_lfconf
        bb.putInt(12000);   // v_hf
        bb.putInt(25000);   // peak_v
        bb.putInt(88);      // peak_f, a divisor
        bb.putInt(95);      // divisor
        for (int i = 0; i < Pm3AntennaTuning.SWEEP_SAMPLES; ++i) {
            bb.put((byte) i);
        }

        Pm3AntennaTuning tuning = Pm3AntennaTuning.parse(bb.array());

        assertEquals(21000, tuning.getVoltageLf134());
        assertEquals(24000, tuning.getVoltageLf125());
        assertEquals(23000, tuning.getVoltageLfConfigured());
        assertEquals(12000, tuning.getVoltageHf());
        assertEquals(25000, tuning.getPeakVoltage());
        assertEquals(88, tuning.getPeakDivisor());
        assertEquals(95, tuning.getSamplingDivisor());

        // Each sample is scaled back up by the firmware's "adcval >> 9".
        assertEquals(0, tuning.getSweepMillivolts()[0]);
        assertEquals(1L << 9, tuning.getSweepMillivolts()[1]);
        assertEquals(255L << 9, tuning.getSweepMillivolts()[255]);
    }

    @Test
    public void convertsADivisorToAFrequency() {
        // LF_DIV2FREQ(95) is the 125 kHz the live LF measurement parks at.
        assertEquals(125000f, Pm3AntennaTuning.divisorToFrequency(95), 1f);
        assertEquals(134831f, Pm3AntennaTuning.divisorToFrequency(88), 1f);
    }

    @Test
    public void rejectsATuningReplyMissingItsSweep() {
        assertNull(Pm3AntennaTuning.parse(new byte[28]));
    }

    // ---- Pm3TuneMode ----

    @Test
    public void buildsTuneModePayloads() {
        // The firmware rejects an LF payload that is not exactly two bytes, and an HF one that is
        // not exactly one.
        assertArrayEquals(
                new byte[]{Proxmark3CommandNG.TUNE_MODE_START,
                        (byte) Proxmark3CommandNG.LF_DIVISOR_125},
                Pm3TuneMode.toBytes(true, Proxmark3CommandNG.TUNE_MODE_START));
        assertArrayEquals(
                new byte[]{Proxmark3CommandNG.TUNE_MODE_STOP},
                Pm3TuneMode.toBytes(false, Proxmark3CommandNG.TUNE_MODE_STOP));
    }

    @Test
    public void readsBothVoltageWidths() {
        assertEquals(Long.valueOf(45000),
                Pm3TuneMode.parseVoltage(buffer(4).putInt(45000).array()));
        assertEquals(Long.valueOf(45000),
                Pm3TuneMode.parseVoltage(buffer(2).putShort((short) 45000).array()));

        // 45000 wraps a signed short; it must still come back positive.
        assertEquals(Long.valueOf(70000),
                Pm3TuneMode.parseVoltage(buffer(4).putInt(70000).array()));
    }

    @Test
    public void rejectsAVoltageOfAnUnknownWidth() {
        assertNull(Pm3TuneMode.parseVoltage(new byte[3]));
        assertNull(Pm3TuneMode.parseVoltage(new byte[0]));
        assertNull(Pm3TuneMode.parseVoltage(null));
    }

    // ---- Pm3MfReadBlock ----

    @Test
    public void buildsAMifareReadBlockPayload() {
        byte[] key = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff};

        assertArrayEquals(
                new byte[]{4, Pm3MfReadBlock.KEY_TYPE_B, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                        (byte) 0xff, (byte) 0xff, (byte) 0xff},
                Pm3MfReadBlock.toBytes(4, Pm3MfReadBlock.KEY_TYPE_B, key));
    }

    @Test
    public void takesOnlySixKeyBytes() {
        byte[] overlongKey = {1, 2, 3, 4, 5, 6, 7, 8};

        assertEquals(Pm3MfReadBlock.SIZE,
                Pm3MfReadBlock.toBytes(0, Pm3MfReadBlock.KEY_TYPE_A, overlongKey).length);
    }

    // ---- Pm3LfHidSim ----

    @Test
    public void buildsAShortFormatHidClonePayload() {
        byte[] payload = Pm3LfHidSim.toBytes(BigInteger.valueOf(0x2004253aeL), false, false);

        assertEquals(Pm3LfHidSim.SIZE, payload.length);
        assertArrayEquals(
                new byte[]{
                        0, 0, 0, 0,                     // hi2
                        2, 0, 0, 0,                     // hi
                        (byte) 0xae, 0x53, 0x42, 0x00,  // lo
                        0,                              // longFMT
                        0,                              // Q5
                        0                               // EM
                },
                payload);
    }

    @Test
    public void marksWideDataAsLongFormat() {
        BigInteger wide = BigInteger.ONE.shiftLeft(Pm3LfHidSim.LONG_FORMAT_BIT_LENGTH);

        assertEquals(1, Pm3LfHidSim.toBytes(wide, false, false)[12]);
        assertEquals(0,
                Pm3LfHidSim.toBytes(BigInteger.ONE.shiftLeft(10), false, false)[12]);
    }

    @Test
    public void carriesTheQ5AndEmFlags() {
        byte[] payload = Pm3LfHidSim.toBytes(BigInteger.ONE, true, true);

        assertEquals(1, payload[13]);
        assertEquals(1, payload[14]);
    }

    // ---- Pm3Iso14aCardSelect ----

    @Test
    public void parsesAFourByteUidCard() {
        ByteBuffer bb = buffer(Pm3Iso14aCardSelect.HEADER_SIZE);
        bb.put(new byte[]{0x04, 0x12, 0x34, 0x56, 0, 0, 0, 0, 0, 0}); // uid[10]
        bb.put((byte) 4);                                             // uidlen
        bb.put(new byte[]{0x44, 0x00});                               // atqa[2]
        bb.put((byte) 0x08);                                          // sak
        bb.put((byte) 0);                                             // ats_len

        Pm3Iso14aCardSelect card = Pm3Iso14aCardSelect.parse(bb.array());

        assertEquals(new BigInteger("04123456", 16), card.getUid());
        assertEquals((short) 0x0044, card.getAtqa());
        assertEquals(0x08, card.getSak());
        assertEquals(0, card.getAts().length);
    }

    @Test
    public void parsesACardThatReturnedAnAts() {
        byte[] ats = {0x78, (byte) 0x80, (byte) 0xb0, 0x02};
        ByteBuffer bb = buffer(Pm3Iso14aCardSelect.HEADER_SIZE + ats.length);
        bb.put(new byte[]{0x04, 0x12, 0x34, 0x56, 0x78, (byte) 0x9a, (byte) 0xbc, 0, 0, 0});
        bb.put((byte) 7);
        bb.put(new byte[]{0x44, 0x03});
        bb.put((byte) 0x20);
        bb.put((byte) ats.length);
        bb.put(ats);

        Pm3Iso14aCardSelect card = Pm3Iso14aCardSelect.parse(bb.array());

        assertEquals(new BigInteger("04123456789abc", 16), card.getUid());
        assertArrayEquals(ats, card.getAts());
    }

    @Test
    public void doesNotReadPastAnAtsLengthThatOverstatesThePayload() {
        ByteBuffer bb = buffer(Pm3Iso14aCardSelect.HEADER_SIZE + 2);
        bb.put(new byte[10]);
        bb.put((byte) 4);
        bb.put(new byte[]{0, 0});
        bb.put((byte) 0);
        bb.put((byte) 255); // claims 255 bytes of ATS, sends 2
        bb.put(new byte[]{1, 2});

        assertEquals(2, Pm3Iso14aCardSelect.parse(bb.array()).getAts().length);
    }

    @Test
    public void rejectsATruncatedCardSelect() {
        assertNull(Pm3Iso14aCardSelect.parse(new byte[Pm3Iso14aCardSelect.HEADER_SIZE - 1]));
    }
}
