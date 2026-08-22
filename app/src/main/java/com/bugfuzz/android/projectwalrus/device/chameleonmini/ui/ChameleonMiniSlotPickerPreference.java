/*
 * Copyright 2018 Daniel Underhay & Matthew Daley.
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

package com.bugfuzz.android.projectwalrus.device.chameleonmini.ui;


import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.preference.DialogPreference;
import androidx.fragment.app.DialogFragment;
import android.util.AttributeSet;
import android.widget.NumberPicker;

import com.bugfuzz.android.projectwalrus.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;


public class ChameleonMiniSlotPickerPreference extends DialogPreference {

    private int value;
    private final int minSlot;
    private final int maxSlot;
    //private int mDialogLayoutResId = R.xml.preferences_chameleon_mini_rev_g;

    public ChameleonMiniSlotPickerPreference(Context context) {
        this(context, null);
    }

    public ChameleonMiniSlotPickerPreference(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ChameleonMiniSlotPickerPreference(Context context, AttributeSet attrs,
            int defStyleAttr) {
        this(context, attrs, defStyleAttr, defStyleAttr);
    }

    public ChameleonMiniSlotPickerPreference(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.ChameleonMiniSlotPickerPreference,
                defStyleAttr, 0);
        minSlot = a.getInt(R.styleable.ChameleonMiniSlotPickerPreference_minSlot, 1);
        maxSlot = a.getInt(R.styleable.ChameleonMiniSlotPickerPreference_maxSlot, 8);
        a.recycle();
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
        persistInt(value);
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return a.getInt(index, 1);
    }

    @Override
    protected void onSetInitialValue(boolean restorePersistedValue,
            Object defaultValue) {
        setValue(restorePersistedValue ? getPersistedInt(value) : (int) defaultValue);
    }

    /*@Override
    public int getDialogLayoutResource() {
        return mDialogLayoutResId;
    }*/

    public static class NumberPickerFragment extends DialogFragment {

        private ChameleonMiniSlotPickerPreference getPreference(){
            return ((ChameleonMiniSlotPickerPreference)
                    (((DialogPreference.TargetFragment)getParentFragment()).findPreference(getArguments().getString("key"))));
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(final Bundle savedInstanceState) {
            final NumberPicker np = new NumberPicker(getContext());
            np.setMinValue(getPreference().minSlot);
            np.setMaxValue(getPreference().maxSlot);
            np.setValue(getPreference().getValue());
            return new MaterialAlertDialogBuilder(getActivity())
                    .setTitle("Default Card Slot")
                    .setView(np)
                    .setPositiveButton("Set", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            getPreference().setValue(np.getValue());
                            dialog.dismiss();
                        }
                    })
                    .setNegativeButton(R.string.cancel_button,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                            })
                    .create();
        }
    }

}