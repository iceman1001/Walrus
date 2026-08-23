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
 * HID Seos. A JavaCard applet rather than a fixed memory layout, addressed over ISO 14443-A, so
 * what identifies it is the UID plus the ADF it holds. Reading a credential out needs the ADF's
 * keys, which is why this stub stops at the identifiers.
 */
@Parcel
@CardData.Metadata(
        name = "SEOS",
        iconId = R.drawable.drawable_seos,
        backgroundColorId = R.color.cardBackgroundSeos,
        textColorId = R.color.cardTextSeos
)
public class SeosCardData extends SerialNumberCardData {

    /** The object identifier of the Application Data File, if it was read. */
    @Nullable
    public String adfOid;

    public SeosCardData() {
        super();
    }

    public SeosCardData(byte[] uid) {
        super(uid);
    }


    public static SeosCardData newDebugInstance() {
        SeosCardData cardData = new SeosCardData(randomSerialNumber(8));
        cardData.adfOid = "2.16.840.1.114416.1.1.1";

        return cardData;
    }

    @Override
    protected String getSerialNumberLabel() {
        return "UID";
    }

    @Nullable
    @Override
    protected String getTechnologyDetail() {
        return adfOid != null
                ? "Seos · ADF " + adfOid
                : "Seos · ADF not read";
    }
}
