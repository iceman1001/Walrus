/*
 * Copyright 2018 Daniel Underhay & Matthew Daley.
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

package com.bugfuzz.android.projectwalrus.device.proxmark3.ui;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.bugfuzz.android.projectwalrus.R;
import com.bugfuzz.android.projectwalrus.device.CardDevice;
import com.bugfuzz.android.projectwalrus.device.CardDeviceManager;
import com.bugfuzz.android.projectwalrus.device.proxmark3.Proxmark3Device;
import com.bugfuzz.android.projectwalrus.device.ui.FindVersionFragment;
import com.bugfuzz.android.projectwalrus.util.WindowInsetsUtils;

import java.io.IOException;
import java.lang.ref.WeakReference;

public class Proxmark3Activity extends AppCompatActivity
        implements FindVersionFragment.OnFindVersionCallback,
        Proxmark3TuneFragment.OnTuneResultCallback {

    private static final String EXTRA_DEVICE =
            "com.bugfuzz.android.projectwalrus.device.proxmark3.Proxmark3Activity.EXTRA_DEVICE";

    private static final String PROXMARK3_TUNE_DIALOG_FRAGMENT_TAG = "proxmark3_tune_dialog";

    private static final String TAG = "Proxmark3Activity";

    private Proxmark3Device proxmark3Device;

    public static Intent getStartActivityIntent(Context context, Proxmark3Device device) {
        Intent intent = new Intent(context, Proxmark3Activity.class);
        intent.putExtra(EXTRA_DEVICE, device.getId());
        return intent;
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_proxmark3);
        WindowInsetsUtils.insetContentBySystemBars(this);

        CardDevice cardDevice = CardDeviceManager.INSTANCE.getCardDevices().get(
                getIntent().getIntExtra(EXTRA_DEVICE, -1));
        if (cardDevice == null) {
            finish();
            return;
        }
        try {
            proxmark3Device = (Proxmark3Device) cardDevice;
        } catch (ClassCastException e) {
            finish();
            return;
        }

        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));

        getSupportFragmentManager().beginTransaction()
                .add(FindVersionFragment.show(proxmark3Device), "find_version_fragment_id")
                .commit();
    }

    @Override
    public void onVersionResult(String version) {
        ((TextView) findViewById(R.id.version)).setText(version);

        // Only once the version is in: both go through the device's single-operation lock, so
        // asking for them at the same time would just fail as "device is busy".
        new FindCapabilitiesTask(proxmark3Device).execute();
    }

    @Override
    public void onVersionError(IOException exception) {
        ((TextView) findViewById(R.id.version)).setText(getString(R.string.failed_get_version,
                exception.getMessage()));
    }

    /**
     * The two band buttons run the continuous measurement, which is what the client's
     * {@code lf tune} and {@code hf tune} do. The sweep that draws a resonance curve is
     * {@code hw tune}, and lives behind its own button.
     */
    public void onTuneLFClick(View view) {
        startActivity(Proxmark3LiveTuneActivity.getStartActivityIntent(this, proxmark3Device,
                true));
    }

    public void onTuneHFClick(View view) {
        startActivity(Proxmark3LiveTuneActivity.getStartActivityIntent(this, proxmark3Device,
                false));
    }

    public void onSweepClick(View view) {
        tune(true);
    }

    private void tune(boolean lf) {
        new Proxmark3TuneDialogFragment().show(getSupportFragmentManager(),
                PROXMARK3_TUNE_DIALOG_FRAGMENT_TAG);
        getSupportFragmentManager().beginTransaction()
                .add(Proxmark3TuneFragment.create(proxmark3Device, lf), "proxmark3_tune")
                .commit();
    }

    private void removeTuneDialog() {
        Fragment proxmark3TuneDialogFragment =
                getSupportFragmentManager().findFragmentByTag(PROXMARK3_TUNE_DIALOG_FRAGMENT_TAG);
        if (proxmark3TuneDialogFragment != null) {
            getSupportFragmentManager().beginTransaction()
                    .remove(proxmark3TuneDialogFragment)
                    .commit();
        }
    }

    @Override
    public void onTuneResult(Proxmark3Device.TuneResult result) {
        removeTuneDialog();

        Proxmark3TuneResultActivity.startActivity(this, result);
    }

    @Override
    public void onTuneError(IOException exception) {
        removeTuneDialog();

        Toast.makeText(this, getString(R.string.failed_to_tune, exception.getMessage()),
                Toast.LENGTH_LONG).show();
    }

    /**
     * Warms the device's capabilities cache, which is what tells a Proxmark3 from a Proxmark5 and
     * what says which of LF and HF this particular firmware was built with.
     *
     * <p>Nothing is shown for it yet; for now the point is to have it fetched and logged by the
     * time anything wants to ask. Failures are not worth reporting to the user, since the device
     * falls back to a conservative set of assumptions on its own.
     */
    private static class FindCapabilitiesTask extends AsyncTask<Void, Void, Void> {

        private final WeakReference<Proxmark3Device> proxmark3DeviceWeakReference;

        FindCapabilitiesTask(Proxmark3Device proxmark3Device) {
            this.proxmark3DeviceWeakReference = new WeakReference<>(proxmark3Device);
        }

        @Override
        protected Void doInBackground(Void... params) {
            Proxmark3Device proxmark3Device = proxmark3DeviceWeakReference.get();
            if (proxmark3Device == null) {
                return null;
            }

            try {
                proxmark3Device.getCapabilities();
            } catch (IOException exception) {
                Log.d(TAG, "Failed to get capabilities", exception);
            }

            return null;
        }
    }
}
