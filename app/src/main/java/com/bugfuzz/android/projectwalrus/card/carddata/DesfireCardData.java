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
 * NXP MIFARE DESFire, any of EV1 through EV3. The UID is free, as is the version information the
 * card returns before authentication; the application directory needs keys.
 *
 * <p>Note that a DESFire configured for random UID hands out a fresh 4 byte value on every select,
 * so a stored UID is not necessarily a stable identifier for the card.
 */
@Parcel
@CardData.Metadata(
        name = "DESFire",
        iconId = R.drawable.drawable_desfire,
        backgroundColorId = R.color.cardBackgroundDesfire,
        textColorId = R.color.cardTextDesfire
)
public class DesfireCardData extends SerialNumberCardData {

    /** What GetVersion() reported, e.g. "EV2". Null until read. */
    @Nullable
    public String hardwareVersion;

    /** How many applications the card holds, or -1 when that has not been read. */
    public int applicationCount;

    public DesfireCardData() {
        super();

        applicationCount = -1;
    }

    public DesfireCardData(byte[] uid) {
        super(uid);

        applicationCount = -1;
    }


    public static DesfireCardData newDebugInstance() {
        // NXP UIDs start with the 0x04 manufacturer byte.
        byte[] uid = randomSerialNumber(7);
        uid[0] = 0x04;

        DesfireCardData cardData = new DesfireCardData(uid);
        cardData.hardwareVersion = "EV2";
        cardData.applicationCount = 3;

        return cardData;
    }

    @Override
    protected String getSerialNumberLabel() {
        return "UID";
    }

    @Nullable
    @Override
    protected String getTechnologyDetail() {
        StringBuilder detail = new StringBuilder("DESFire");

        if (hardwareVersion != null) {
            detail.append(' ').append(hardwareVersion);
        }

        detail.append(" · ");
        detail.append(applicationCount >= 0
                ? applicationCount + (applicationCount == 1 ? " application" : " applications")
                : "applications not read");

        return detail.toString();
    }
}
