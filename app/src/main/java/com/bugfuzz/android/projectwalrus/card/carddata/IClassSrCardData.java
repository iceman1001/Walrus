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
 * HID iCLASS SR: the transitional card that holds both a legacy iCLASS credential and an SE style
 * SIO, so that a site can migrate readers without reissuing cards. Which of the two a given reader
 * honours is a reader configuration question, not something visible on the card.
 */
@Parcel
@CardData.Metadata(
        name = "iCLASS SR",
        iconId = R.drawable.drawable_iclass_sr,
        backgroundColorId = R.color.cardBackgroundIClassSr,
        textColorId = R.color.cardTextIClassSr
)
public class IClassSrCardData extends SerialNumberCardData {

    /** The SE side of the card, if one was recovered. Null until read. */
    @Nullable
    public byte[] sio;

    /** Set when the legacy iCLASS credential was also found on the card. */
    public boolean hasLegacyCredential;

    public IClassSrCardData() {
        super();
    }

    public IClassSrCardData(byte[] csn) {
        super(csn);
    }


    public static IClassSrCardData newDebugInstance() {
        byte[] csn = randomSerialNumber(8);
        csn[6] = (byte) 0xe0;
        csn[7] = (byte) 0x12;

        IClassSrCardData cardData = new IClassSrCardData(csn);
        cardData.sio = randomSerialNumber(64);
        cardData.hasLegacyCredential = true;

        return cardData;
    }

    @Override
    protected String getSerialNumberLabel() {
        return "CSN";
    }

    @Nullable
    @Override
    protected String getTechnologyDetail() {
        StringBuilder detail = new StringBuilder("SR · ");
        detail.append(hasLegacyCredential ? "legacy + " : "");
        detail.append(sio != null ? "SIO " + sio.length + " bytes" : "SIO not read");

        return detail.toString();
    }
}
