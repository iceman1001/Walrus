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
 * HID iCLASS SE. The CSN reads the same as on a legacy iCLASS; what differs is that the credential
 * is carried in a signed SIO object rather than in the clear, so recovering the CSN tells you
 * almost nothing about what the card opens.
 */
@Parcel
@CardData.Metadata(
        name = "iCLASS SE",
        iconId = R.drawable.drawable_iclass_se,
        backgroundColorId = R.color.cardBackgroundIClassSe,
        textColorId = R.color.cardTextIClassSe
)
public class IClassSeCardData extends SerialNumberCardData {

    /** The Secure Identity Object, if one was recovered. Null until read. */
    @Nullable
    public byte[] sio;

    public IClassSeCardData() {
        super();
    }

    public IClassSeCardData(byte[] csn) {
        super(csn);
    }


    public static IClassSeCardData newDebugInstance() {
        byte[] csn = randomSerialNumber(8);
        csn[6] = (byte) 0xe0;
        csn[7] = (byte) 0x12;

        IClassSeCardData cardData = new IClassSeCardData(csn);
        cardData.sio = randomSerialNumber(64);

        return cardData;
    }

    @Override
    protected String getSerialNumberLabel() {
        return "CSN";
    }

    @Nullable
    @Override
    protected String getTechnologyDetail() {
        return sio != null
                ? "SE · SIO " + sio.length + " bytes"
                : "SE · SIO not read";
    }
}
