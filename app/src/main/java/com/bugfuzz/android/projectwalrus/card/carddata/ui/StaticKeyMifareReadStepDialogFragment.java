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

package com.bugfuzz.android.projectwalrus.card.carddata.ui;

import android.app.Dialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bugfuzz.android.projectwalrus.R;
import com.bugfuzz.android.projectwalrus.card.carddata.MifareReadStep;
import com.bugfuzz.android.projectwalrus.card.carddata.StaticKeyMifareReadStep;
import com.bugfuzz.android.projectwalrus.databinding.StaticKeyMifareReadStepDialogBinding;
import com.bugfuzz.android.projectwalrus.util.DialogUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

// TODO XXX: setError on views like component dialogs
public class StaticKeyMifareReadStepDialogFragment extends MifareReadStepDialogFragment {

    private StaticKeyMifareReadStepDialogViewModel viewModel;

    @Override
    @NonNull
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        StaticKeyMifareReadStep staticReadStep =
                (StaticKeyMifareReadStep) getArguments().getSerializable("read_step");

        viewModel = new ViewModelProvider(this,
                new StaticKeyMifareReadStepDialogViewModel.Factory(staticReadStep))
                .get(StaticKeyMifareReadStepDialogViewModel.class);

        View customView = DialogUtils.inflateCustomView(requireActivity(),
                R.layout.layout_static_key_mifare_read_step_dialog);

        final Dialog dialog = new MaterialAlertDialogBuilder(getActivity())
                .setTitle(staticReadStep != null ? R.string.edit_mifare_static_key_read_step :
                        R.string.add_mifare_static_key_read_step)
                .setView(DialogUtils.asCustomView(requireActivity(), customView, true))
                .setPositiveButton(staticReadStep != null ? android.R.string.ok : R.string.add,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                viewModel.onAddClick();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        StaticKeyMifareReadStepDialogBinding binding = StaticKeyMifareReadStepDialogBinding.bind(
                customView);
        binding.setLifecycleOwner(this);

        binding.setViewModel(viewModel);

        viewModel.getIsValid().observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(@Nullable Boolean isValid) {
                updatePositiveButton();
            }
        });

        viewModel.getResult().observe(this, new Observer<MifareReadStep>() {
            @Override
            public void onChanged(@Nullable MifareReadStep readStep) {
                ((OnResultCallback) getParentFragment()).onResult(readStep,
                        getArguments().getInt("callback_id"));
            }
        });

        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();

        // The buttons of an AlertDialog only exist once it has been shown, unlike
        // material-dialogs' getActionButton().
        updatePositiveButton();
    }

    private void updatePositiveButton() {
        Dialog dialog = getDialog();
        if (!(dialog instanceof AlertDialog)) {
            return;
        }

        Button button = ((AlertDialog) dialog).getButton(DialogInterface.BUTTON_POSITIVE);
        if (button == null) {
            return;
        }

        Boolean isValid = viewModel.getIsValid().getValue();
        button.setEnabled(isValid != null && isValid);
    }
}
