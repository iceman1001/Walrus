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

package com.bugfuzz.android.projectwalrus.device.ui;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bugfuzz.android.projectwalrus.R;
import com.bugfuzz.android.projectwalrus.device.BulkReadCardDataOperationRunner;
import com.bugfuzz.android.projectwalrus.device.BulkReadCardsService;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class BulkReadCardsDialogFragment extends DialogFragment {

    private BulkReadCardsService.ServiceBinder bulkReadCardsServiceBinder;

    private final BroadcastReceiver serviceUpdateBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getRunner() == null) {
                Dialog dialog = getDialog();
                if (dialog != null) {
                    dialog.cancel();
                }
            }
        }
    };
    private CardDataIOView cardDataIOView;
    private final BroadcastReceiver runnerUpdateBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            BulkReadCardDataOperationRunner runner = getRunner();
            if (runner != null) {
                cardDataIOView.setStatus(getResources().getQuantityString(
                        R.plurals.num_cards_read, runner.getNumberOfCardsRead(),
                        runner.getNumberOfCardsRead()));
            }
        }
    };
    private final ServiceConnection bulkReadCardsServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder binder) {
            bulkReadCardsServiceBinder = (BulkReadCardsService.ServiceBinder) binder;

            BulkReadCardDataOperationRunner runner = getRunner();
            if (runner != null) {
                cardDataIOView.setCardDeviceClass(runner.getCardDevice().getClass());
                cardDataIOView.setCardDataClass(runner.getCardDataClass());
                cardDataIOView.setStatus(getResources().getQuantityString(
                        R.plurals.num_cards_read, runner.getNumberOfCardsRead(),
                        runner.getNumberOfCardsRead()));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            bulkReadCardsServiceBinder = null;

            Dialog dialog = getDialog();
            if (dialog != null) {
                dialog.cancel();
            }
        }
    };

    public static BulkReadCardsDialogFragment create(BulkReadCardDataOperationRunner runner,
            int callbackId) {
        BulkReadCardsDialogFragment dialog = new BulkReadCardsDialogFragment();

        Bundle args = new Bundle();
        args.putInt("runner_id", runner.getId());
        args.putInt("callback_id", callbackId);
        dialog.setArguments(args);

        return dialog;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        cardDataIOView = new CardDataIOView(getActivity());
        cardDataIOView.setDirection(true);

        return new MaterialAlertDialogBuilder(getActivity())
                .setTitle(R.string.bulk_reading_cards)
                .setView(cardDataIOView)
                .setPositiveButton(R.string.stop_button,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                BulkReadCardDataOperationRunner runner = getRunner();
                                if (runner != null) {
                                    runner.stopReading();
                                }
                            }
                        })
                .create();
    }

    @Override
    public void onResume() {
        super.onResume();

        getActivity().bindService(new Intent(getActivity(), BulkReadCardsService.class),
                bulkReadCardsServiceConnection, 0);

        LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(
                getActivity());
        localBroadcastManager.registerReceiver(
                runnerUpdateBroadcastReceiver,
                new IntentFilter(BulkReadCardDataOperationRunner.ACTION_UPDATE));
        localBroadcastManager.registerReceiver(
                serviceUpdateBroadcastReceiver,
                new IntentFilter(BulkReadCardsService.ACTION_UPDATE));
    }

    @Override
    public void onPause() {
        super.onPause();

        LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(
                getActivity());
        localBroadcastManager.unregisterReceiver(serviceUpdateBroadcastReceiver);
        localBroadcastManager.unregisterReceiver(runnerUpdateBroadcastReceiver);

        getActivity().unbindService(bulkReadCardsServiceConnection);
    }

    private BulkReadCardDataOperationRunner getRunner() {
        return bulkReadCardsServiceBinder != null
                ? bulkReadCardsServiceBinder.getRunners().get(getArguments().getInt("runner_id"))
                : null;
    }
}
