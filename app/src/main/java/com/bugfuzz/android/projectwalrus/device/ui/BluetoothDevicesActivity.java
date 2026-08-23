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

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bugfuzz.android.projectwalrus.R;
import com.bugfuzz.android.projectwalrus.device.CardDeviceManager;
import com.bugfuzz.android.projectwalrus.util.WindowInsetsUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds, pairs with and connects to a Bluetooth reader.
 *
 * <p>The Proxmark3's Blue Shark add-on is a Bluetooth Classic module, so this is ordinary
 * discovery and bonding rather than anything BLE. The PIN is 1234 out of the box; Android puts up
 * its own PIN prompt during bonding, which is why nothing here tries to supply it.
 */
public class BluetoothDevicesActivity extends AppCompatActivity
        implements BluetoothDeviceAdapter.OnBluetoothDeviceClickCallback {

    private static final int PERMISSION_REQUEST_CODE = 200;

    private final BluetoothDeviceAdapter adapter = new BluetoothDeviceAdapter(this);

    private final Map<String, BluetoothDevice> discovered = new LinkedHashMap<>();

    private BluetoothAdapter bluetoothAdapter;
    private RecyclerView recyclerView;
    private ProgressBar scanProgress;
    private TextView empty;

    private BluetoothDevice pendingConnect;
    private boolean scanning;

    private final BroadcastReceiver bluetoothBroadcastReceiver = new BroadcastReceiver() {
        @Override
        @SuppressLint("MissingPermission")
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }

            switch (action) {
                case BluetoothDevice.ACTION_FOUND: {
                    BluetoothDevice device = intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        discovered.put(device.getAddress(), device);
                        refresh();
                    }
                    break;
                }

                case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
                    scanning = true;
                    invalidateOptionsMenu();
                    refresh();
                    break;

                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    scanning = false;
                    invalidateOptionsMenu();
                    refresh();
                    break;

                case BluetoothDevice.ACTION_BOND_STATE_CHANGED: {
                    BluetoothDevice device = intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE);
                    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                            BluetoothDevice.BOND_NONE);

                    if (device == null || pendingConnect == null
                            || !device.getAddress().equals(pendingConnect.getAddress())) {
                        refresh();
                        break;
                    }

                    if (state == BluetoothDevice.BOND_BONDED) {
                        BluetoothDevice bonded = pendingConnect;
                        pendingConnect = null;
                        connect(bonded);
                    } else if (state == BluetoothDevice.BOND_NONE) {
                        String name = safeName(device);
                        pendingConnect = null;
                        Toast.makeText(BluetoothDevicesActivity.this,
                                getString(R.string.bluetooth_pair_failed, name),
                                Toast.LENGTH_LONG).show();
                    }

                    refresh();
                    break;
                }

                default:
                    break;
            }
        }
    };

    private final BroadcastReceiver connectResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String error = intent.getStringExtra(CardDeviceManager.EXTRA_ERROR);

            if (error != null) {
                Toast.makeText(BluetoothDevicesActivity.this, error, Toast.LENGTH_LONG).show();
                refresh();
            } else {
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_bluetooth_devices);
        WindowInsetsUtils.insetContentBySystemBars(this);

        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.bluetooth_devices_activity_name);
        }

        recyclerView = findViewById(R.id.bluetooth_device_list);
        recyclerView.setAdapter(adapter);

        scanProgress = findViewById(R.id.scan_progress);
        empty = findViewById(R.id.empty);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.bluetooth_unsupported, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        ContextCompat.registerReceiver(this, bluetoothBroadcastReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        LocalBroadcastManager.getInstance(this).registerReceiver(connectResultReceiver,
                new IntentFilter(CardDeviceManager.ACTION_BLUETOOTH_CONNECT_RESULT));

        if (hasPermissions()) {
            refresh();
        } else {
            requestPermissions();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        stopScan();

        unregisterReceiver(bluetoothBroadcastReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(connectResultReceiver);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_bluetooth_devices, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.scan).setTitle(
                scanning ? R.string.bluetooth_stop_scan : R.string.bluetooth_scan);
        menu.findItem(R.id.forget).setVisible(
                CardDeviceManager.INSTANCE.getRememberedBluetoothAddress(this) != null);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.scan) {
            if (scanning) {
                stopScan();
            } else {
                startScan();
            }
            return true;
        }

        if (id == R.id.forget) {
            CardDeviceManager.INSTANCE.forgetBluetoothDevice(this);
            Toast.makeText(this, R.string.bluetooth_forgotten, Toast.LENGTH_SHORT).show();
            invalidateOptionsMenu();
            refresh();
            return true;
        }

        if (id == android.R.id.home) {
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    // ---------------------------------------------------------------- permissions

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
            };
        }

        // Before API 31 the BLUETOOTH / BLUETOOTH_ADMIN permissions are install-time, but
        // Classic discovery still needs a location permission at runtime.
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPermissions() {
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        return true;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != PERMISSION_REQUEST_CODE) {
            return;
        }

        if (hasPermissions()) {
            refresh();
        } else {
            Toast.makeText(this, R.string.bluetooth_permission_rationale, Toast.LENGTH_LONG)
                    .show();
            finish();
        }
    }

    // ---------------------------------------------------------------- scanning

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, R.string.bluetooth_disabled, Toast.LENGTH_LONG).show();
            return;
        }

        discovered.clear();

        try {
            bluetoothAdapter.startDiscovery();
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.bluetooth_permission_missing, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (bluetoothAdapter == null || !hasPermissions()) {
            return;
        }

        // CHECKSTYLE:OFF EmptyCatchBlock
        try {
            bluetoothAdapter.cancelDiscovery();
        } catch (SecurityException ignored) {
        }
        // CHECKSTYLE:ON EmptyCatchBlock
    }

    @SuppressLint("MissingPermission")
    private void refresh() {
        List<BluetoothDevice> paired = new ArrayList<>();

        if (bluetoothAdapter != null && hasPermissions() && bluetoothAdapter.isEnabled()) {
            // CHECKSTYLE:OFF EmptyCatchBlock
            try {
                paired.addAll(bluetoothAdapter.getBondedDevices());
            } catch (SecurityException ignored) {
            }
            // CHECKSTYLE:ON EmptyCatchBlock
        }

        List<BluetoothDevice> available = new ArrayList<>();
        for (BluetoothDevice device : discovered.values()) {
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                available.add(device);
            }
        }

        adapter.setDevices(paired, available,
                CardDeviceManager.INSTANCE.getRememberedBluetoothAddress(this));

        empty.setVisibility(adapter.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(adapter.isEmpty() ? View.GONE : View.VISIBLE);
        scanProgress.setVisibility(scanning ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- connecting

    @Override
    @SuppressLint("MissingPermission")
    public void onBluetoothDeviceClick(BluetoothDevice bluetoothDevice) {
        stopScan();

        if (bluetoothDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
            connect(bluetoothDevice);
            return;
        }

        pendingConnect = bluetoothDevice;

        Toast.makeText(this, getString(R.string.bluetooth_pair_hint, safeName(bluetoothDevice)),
                Toast.LENGTH_LONG).show();

        try {
            if (!bluetoothDevice.createBond()) {
                pendingConnect = null;
                Toast.makeText(this,
                        getString(R.string.bluetooth_pair_failed, safeName(bluetoothDevice)),
                        Toast.LENGTH_LONG).show();
            }
        } catch (SecurityException e) {
            pendingConnect = null;
            Toast.makeText(this, R.string.bluetooth_permission_missing, Toast.LENGTH_LONG).show();
        }
    }

    private void connect(final BluetoothDevice bluetoothDevice) {
        Toast.makeText(this, getString(R.string.bluetooth_connecting, safeName(bluetoothDevice)),
                Toast.LENGTH_SHORT).show();

        final Context context = getApplicationContext();

        new Thread(new Runnable() {
            @Override
            public void run() {
                CardDeviceManager.INSTANCE.connectBluetoothDevice(context, bluetoothDevice, true);
            }
        }, "walrus-bt-connect").start();
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            if (name != null) {
                return name;
            }
        } catch (SecurityException ignored) {
            // Fall through to the address.
        }

        return device.getAddress();
    }
}
