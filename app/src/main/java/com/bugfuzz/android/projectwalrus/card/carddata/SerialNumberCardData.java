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

package com.bugfuzz.android.projectwalrus.card.carddata;

import androidx.annotation.Nullable;

import com.bugfuzz.android.projectwalrus.util.MiscUtils;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.util.Arrays;
import java.util.Random;

/**
 * A card that is, so far, only known by its serial number.
 *
 * <p>Every technology below this reads out an immutable factory serial in the clear — a CSN on the
 * iCLASS family, a UID on the 14443-A ones — and needs keys before anything more can be had. These
 * subclasses are deliberately stubs: they model the identity and whatever the reader could tell
 * without authenticating, and nothing else. The extra fields are all optional and stay null until
 * something actually fills them in.
 */
public abstract class SerialNumberCardData extends CardData {

    /** As read off the card, most significant byte first. Never null; empty means unknown. */
    public byte[] serialNumber;

    protected SerialNumberCardData() {
        serialNumber = new byte[0];
    }

    protected SerialNumberCardData(byte[] serialNumber) {
        this.serialNumber = serialNumber != null ? serialNumber : new byte[0];
    }

    /** What this technology calls its serial: "CSN" on iCLASS, "UID" on the 14443-A cards. */
    protected abstract String getSerialNumberLabel();

    /**
     * The technology line under the serial: what was worked out about the card beyond its number.
     * Null when nothing is known yet, which is the usual case for a card that has only been
     * enrolled by hand.
     */
    @Nullable
    protected String getTechnologyDetail() {
        return null;
    }

    /** A plausible serial for the debug device to hand out. */
    protected static byte[] randomSerialNumber(int length) {
        byte[] serialNumber = new byte[length];
        new Random().nextBytes(serialNumber);

        return serialNumber;
    }

    public boolean hasSerialNumber() {
        return serialNumber.length > 0;
    }

    public String getSerialNumberHex() {
        return MiscUtils.bytesToHex(serialNumber, false);
    }

    @Override
    public String getHumanReadableText() {
        StringBuilder result = new StringBuilder();

        result.append(getSerialNumberLabel()).append(' ');
        result.append(hasSerialNumber() ? getSerialNumberHex() : "unknown");

        String detail = getTechnologyDetail();
        if (detail != null) {
            result.append('\n').append(detail);
        }

        return result.toString();
    }

    @Nullable
    @Override
    public String getTypeDetailInfo() {
        return getTechnologyDetail();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        return new EqualsBuilder()
                .append(serialNumber, ((SerialNumberCardData) o).serialNumber)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37)
                .append(serialNumber)
                .toHashCode();
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        SerialNumberCardData copy = (SerialNumberCardData) super.clone();
        copy.serialNumber = Arrays.copyOf(serialNumber, serialNumber.length);

        return copy;
    }
}
