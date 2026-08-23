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

package com.bugfuzz.android.projectwalrus.card.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.bugfuzz.android.projectwalrus.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class DeleteCardConfirmDialogFragment extends DialogFragment {

    public static DeleteCardConfirmDialogFragment create(int callbackId) {
        DeleteCardConfirmDialogFragment dialog = new DeleteCardConfirmDialogFragment();

        Bundle args = new Bundle();
        args.putInt("callback_id", callbackId);
        dialog.setArguments(args);

        return dialog;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        if (!(context instanceof OnDeleteCardConfirmCallback)) {
            throw new RuntimeException("Parent doesn't implement fragment callback interface");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        return new MaterialAlertDialogBuilder(getActivity())
                .setTitle(R.string.delete_card)
                .setMessage(R.string.delete_message)
                .setPositiveButton(R.string.delete_button,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                ((OnDeleteCardConfirmCallback) getActivity())
                                        .onDeleteCardConfirm(
                                                getArguments().getInt("callback_id"));
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

    public interface OnDeleteCardConfirmCallback {
        void onDeleteCardConfirm(int callbackId);
    }
}
