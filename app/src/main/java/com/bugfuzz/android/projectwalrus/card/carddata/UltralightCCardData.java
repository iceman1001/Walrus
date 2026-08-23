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
 * NXP MIFARE Ultralight C: 192 bytes of user memory behind a single 3DES authentication, with a
 * 7 byte UID that is always readable.
 *
 * <p>Ultralight C is told apart from a plain Ultralight by whether it answers the authenticate
 * command at all, not by its ATQA or SAK, which are shared across the family.
 */
@Parcel
@CardData.Metadata(
        name = "UL-C",
        iconId = R.drawable.drawable_ultralight_c,
        backgroundColorId = R.color.cardBackgroundUltralightC,
        textColorId = R.color.cardTextUltralightC
)
public class UltralightCCardData extends SerialNumberCardData {

    /** The eight bytes GET_VERSION returns, if the card was asked. Null until read. */
    @Nullable
    public byte[] versionInfo;

    /** Set once the card has been seen to answer the 3DES authenticate command. */
    public boolean authenticationConfirmed;

    public UltralightCCardData() {
        super();
    }

    public UltralightCCardData(byte[] uid) {
        super(uid);
    }


    public static UltralightCCardData newDebugInstance() {
        byte[] uid = randomSerialNumber(7);
        uid[0] = 0x04;

        UltralightCCardData cardData = new UltralightCCardData(uid);
        cardData.authenticationConfirmed = true;

        return cardData;
    }

    @Override
    protected String getSerialNumberLabel() {
        return "UID";
    }

    @Nullable
    @Override
    protected String getTechnologyDetail() {
        return authenticationConfirmed
                ? "Ultralight C · 3DES confirmed"
                : "Ultralight C · 3DES unconfirmed";
    }
}
