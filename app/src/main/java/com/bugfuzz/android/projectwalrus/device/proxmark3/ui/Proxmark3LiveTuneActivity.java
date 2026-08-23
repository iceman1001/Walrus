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

package com.bugfuzz.android.projectwalrus.device.proxmark3.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.bugfuzz.android.projectwalrus.R;
import com.bugfuzz.android.projectwalrus.device.CardDeviceManager;
import com.bugfuzz.android.projectwalrus.device.proxmark3.Proxmark3Device;
import com.bugfuzz.android.projectwalrus.util.WindowInsetsUtils;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The live antenna measurement, matching the client's {@code lf tune} and {@code hf tune}: the
 * field is parked at one frequency and the voltage is read continuously until you stop it.
 *
 * <p>Distinct from the LF sweep behind {@code hw tune}, which produces the resonance curve in
 * {@link Proxmark3TuneResultActivity}.
 */
public class Proxmark3LiveTuneActivity extends AppCompatActivity {

    private static final String EXTRA_DEVICE_ID =
            "com.bugfuzz.android.projectwalrus.device.proxmark3.ui.Proxmark3LiveTuneActivity"
                    + ".EXTRA_DEVICE_ID";
    private static final String EXTRA_LF =
            "com.bugfuzz.android.projectwalrus.device.proxmark3.ui.Proxmark3LiveTuneActivity"
                    + ".EXTRA_LF";

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView voltageView;
    private TextView peakView;
    private TextView statusView;
    private ProgressBar levelView;

    private long peakMillivolts;

    public static Intent getStartActivityIntent(Context context, Proxmark3Device device,
            boolean lf) {
        Intent intent = new Intent(context, Proxmark3LiveTuneActivity.class);
        intent.putExtra(EXTRA_DEVICE_ID, device.getId());
        intent.putExtra(EXTRA_LF, lf);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_proxmark3_live_tune);
        WindowInsetsUtils.insetContentBySystemBars(this);

        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.live_tune_activity_name);
        }

        voltageView = findViewById(R.id.voltage);
        peakView = findViewById(R.id.peak);
        statusView = findViewById(R.id.status);
        levelView = findViewById(R.id.level);

        final boolean lf = getIntent().getBooleanExtra(EXTRA_LF, true);

        ((TextView) findViewById(R.id.band)).setText(lf
                ? getString(R.string.live_tune_lf,
                        Proxmark3Device.getLiveTuneLfFrequency() / 1e3f)
                : getString(R.string.live_tune_hf));
        statusView.setText(R.string.live_tune_measuring);

        final Proxmark3Device device = (Proxmark3Device) CardDeviceManager.INSTANCE
                .getCardDevices().get(getIntent().getIntExtra(EXTRA_DEVICE_ID, -1));
        if (device == null) {
            finish();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    device.liveTune(lf, new Proxmark3Device.LiveTuneSink() {
                        @Override
                        public boolean wantsMore() {
                            return running.get();
                        }

                        @Override
                        public void onVoltage(final long millivolts) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    showVoltage(millivolts);
                                }
                            });
                        }
                    });

                    post(running.get() ? R.string.live_tune_button_pressed
                            : R.string.live_tune_stopped);
                } catch (final IOException e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusView.setText(e.getMessage());
                        }
                    });
                }
            }
        }, "walrus-live-tune").start();
    }

    private void post(final int stringId) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                statusView.setText(stringId);
            }
        });
    }

    private void showVoltage(long millivolts) {
        voltageView.setText(getString(R.string.live_tune_voltage, millivolts / 1e3f));

        if (millivolts > peakMillivolts) {
            peakMillivolts = millivolts;
            peakView.setText(getString(R.string.live_tune_peak, peakMillivolts / 1e3f));
        }

        // Scaled against the peak seen so far, the way the client's bar display is.
        levelView.setProgress(peakMillivolts > 0
                ? (int) (millivolts * 1000 / peakMillivolts) : 0);
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Stops the loop, which then tells the device to drop the field.
        running.set(false);
    }
}
