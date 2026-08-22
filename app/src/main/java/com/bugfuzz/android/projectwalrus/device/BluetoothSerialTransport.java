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

package com.bugfuzz.android.projectwalrus.device;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

import com.bugfuzz.android.projectwalrus.R;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * A {@link SerialCardDevice.Transport} over a Bluetooth Classic RFCOMM socket.
 *
 * <p>This is what the Proxmark3 RDV4's Blue Shark add-on presents. Per doc/bt_manual_v10.md in
 * the Iceman fork it is a Bluetooth 2.0 + EDR module, not BLE, and the official client reaches
 * it with a plain RFCOMM connection on channel 1 (see the AF_BLUETOOTH / BTPROTO_RFCOMM socket
 * in client/src/uart/uart_posix.c). Channel 1 is what the well known Serial Port Profile UUID
 * resolves to, so {@code createRfcommSocketToServiceRecord} reaches the same place.
 *
 * <p>The module hangs off the same UART the firmware already speaks its protocol on, and the
 * manual notes that the USB and UART interfaces "can coexist without conflict, and no special
 * switching is required" - so the framing above this class is byte for byte the same as over USB.
 */
public class BluetoothSerialTransport implements SerialCardDevice.Transport {

    /** The Serial Port Profile service UUID; resolves to RFCOMM channel 1 on these modules. */
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final long SETTLE_DELAY_MS = 500;

    /**
     * RFIDtools (libcom/AbsBluetoothSpp) retries the connect several times before giving up;
     * these modules do fail the first attempt fairly often. Unlike that implementation this one
     * builds a fresh socket per attempt, because a BluetoothSocket that has failed to connect
     * cannot be reused.
     */
    private static final int CONNECT_ATTEMPTS = 5;

    private static final long RETRY_DELAY_MS = 750;

    private final Context context;
    private final BluetoothDevice bluetoothDevice;

    private BluetoothSocket socket;
    private OutputStream outputStream;
    private Thread readThread;
    private BroadcastReceiver disconnectReceiver;
    private volatile boolean closing;

    public BluetoothSerialTransport(Context context, BluetoothDevice bluetoothDevice) {
        this.context = context;
        this.bluetoothDevice = bluetoothDevice;
    }

    public BluetoothDevice getBluetoothDevice() {
        return bluetoothDevice;
    }

    @Override
    // The caller is responsible for holding BLUETOOTH_CONNECT; BluetoothDevicesActivity asks for
    // it before it ever offers a device to connect to.
    @SuppressLint("MissingPermission")
    public void open(final Listener listener) throws IOException {
        // Discovery is very expensive while a connection is being set up, and the platform
        // documentation asks callers to cancel it first.
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                adapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {
            // Scanning permission is optional for connecting; carry on.
        }

        IOException lastFailure = null;

        for (int attempt = 0; attempt < CONNECT_ATTEMPTS; ++attempt) {
            try {
                socket = bluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                lastFailure = null;
                break;
            } catch (SecurityException e) {
                closeSocketQuietly();
                throw new IOException(context.getString(R.string.bluetooth_permission_missing));
            } catch (IOException e) {
                closeSocketQuietly();
                lastFailure = e;

                // CHECKSTYLE:OFF EmptyCatchBlock
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ignored) {
                }
                // CHECKSTYLE:ON EmptyCatchBlock
            }
        }

        if (lastFailure != null) {
            throw new IOException(context.getString(R.string.bluetooth_connect_failed));
        }

        outputStream = socket.getOutputStream();
        final InputStream inputStream = socket.getInputStream();

        // The RFCOMM link comes up a little before the module's UART bridge is actually passing
        // bytes, and anything written into that gap is swallowed. The first thing Walrus sends is
        // the version handshake, so losing it means the device never reports itself.
        // CHECKSTYLE:OFF EmptyCatchBlock
        try {
            Thread.sleep(SETTLE_DELAY_MS);
        } catch (InterruptedException ignored) {
        }
        // CHECKSTYLE:ON EmptyCatchBlock

        readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] chunk = new byte[1024];

                for (; ; ) {
                    int read;
                    try {
                        read = inputStream.read(chunk);
                    } catch (IOException e) {
                        if (!closing) {
                            listener.onTransportClosed(e);
                        }
                        return;
                    }

                    if (read == -1) {
                        if (!closing) {
                            listener.onTransportClosed(new IOException("Bluetooth stream ended"));
                        }
                        return;
                    }

                    if (read > 0) {
                        byte[] in = new byte[read];
                        System.arraycopy(chunk, 0, in, 0, read);
                        listener.onBytesReceived(in);
                    }
                }
            }
        }, "walrus-bt-read");
        readThread.setDaemon(true);
        readThread.start();

        // A read only fails once the stack notices the link is gone, which can lag. RFIDtools
        // watches ACTION_ACL_DISCONNECTED instead; do both, and let onTransportClosed be the one
        // that deduplicates.
        disconnectReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                if (device != null && device.getAddress().equals(bluetoothDevice.getAddress())
                        && !closing) {
                    listener.onTransportClosed(new IOException("Bluetooth link dropped"));
                }
            }
        };
        ContextCompat.registerReceiver(context, disconnectReceiver,
                new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void write(byte[] bytes) {
        OutputStream out = outputStream;
        if (out == null) {
            return;
        }

        try {
            out.write(bytes);
            out.flush();
        } catch (IOException ignored) {
            // The read thread will notice the same failure and report the disconnection once.
        }
    }

    @Override
    public void close() {
        closing = true;

        if (disconnectReceiver != null) {
            // CHECKSTYLE:OFF EmptyCatchBlock
            try {
                context.unregisterReceiver(disconnectReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            // CHECKSTYLE:ON EmptyCatchBlock

            disconnectReceiver = null;
        }

        closeSocketQuietly();

        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }

        outputStream = null;
    }

    private void closeSocketQuietly() {
        if (socket != null) {
            // CHECKSTYLE:OFF EmptyCatchBlock
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            // CHECKSTYLE:ON EmptyCatchBlock

            socket = null;
        }
    }

    @Override
    public String getDescription() {
        return bluetoothDevice.getAddress();
    }
}
