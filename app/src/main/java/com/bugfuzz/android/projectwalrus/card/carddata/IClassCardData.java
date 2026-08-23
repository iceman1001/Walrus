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

import com.bugfuzz.android.projectwalrus.R;

import org.parceler.Parcel;

/**
 * HID iCLASS, the original: a PicoPass card with the legacy key diversification.
 *
 * <p>The 8 byte CSN comes back from a plain select with no keys at all. Everything past that —
 * the credential in block 7, the application areas — needs the card's key, so a stub holds the CSN
 * and whatever the config block said about the card's size.
 */
@Parcel
@CardData.Metadata(
        name = "iCLASS",
        iconId = R.drawable.drawable_iclass,
        backgroundColorId = R.color.cardBackgroundIClass,
        textColorId = R.color.cardTextIClass
)
public class IClassCardData extends SerialNumberCardData {

    /** Block 1, which carries the app limit and the chip's memory size. Null until read. */
    @Nullable
    public byte[] configBlock;

    public IClassCardData() {
        super();
    }

    public IClassCardData(byte[] csn) {
        super(csn);
    }


    public static IClassCardData newDebugInstance() {
        // iCLASS CSNs are 8 bytes and always end in the PicoPass e0 12 tag.
        byte[] csn = randomSerialNumber(8);
        csn[6] = (byte) 0xe0;
        csn[7] = (byte) 0x12;

        return new IClassCardData(csn);
    }

    @Override
    protected String getSerialNumberLabel() {
        return "CSN";
    }

    @Nullable
    @Override
    protected String getTechnologyDetail() {
        if (configBlock == null) {
            return "PicoPass · config not read";
        }

        return "PicoPass · config " + com.bugfuzz.android.projectwalrus.util.MiscUtils
                .bytesToHex(configBlock, false);
    }
}
