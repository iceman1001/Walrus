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
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.bugfuzz.android.projectwalrus.R;
import com.bugfuzz.android.projectwalrus.device.chameleonmini.ChameleonMiniRevERebootedDevice;
import com.bugfuzz.android.projectwalrus.device.chameleonmini.ChameleonMiniRevGDevice;
import com.bugfuzz.android.projectwalrus.device.proxmark3.Proxmark3Device;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public enum CardDeviceManager {
    INSTANCE;

    public static final String ACTION_UPDATE =
            "com.bugfuzz.android.projectwalrus.device.CardDeviceManager.ACTION_UPDATE";
    public static final String EXTRA_DEVICE_WAS_ADDED =
            "com.bugfuzz.android.projectwalrus.device.CardDeviceManager.EXTRA_DEVICE_WAS_ADDED";
    public static final String EXTRA_DEVICE_ID =
            "com.bugfuzz.android.projectwalrus.device.CardDeviceManager.EXTRA_DEVICE_ID";
    public static final String EXTRA_DEVICE_NAME =
            "com.bugfuzz.android.projectwalrus.device.CardDeviceManager.EXTRA_DEVICE_NAME";

    /** Broadcast once a requested Bluetooth connection has either come up or failed. */
    public static final String ACTION_BLUETOOTH_CONNECT_RESULT =
            "com.bugfuzz.android.projectwalrus.device.CardDeviceManager"
                    + ".ACTION_BLUETOOTH_CONNECT_RESULT";
    public static final String EXTRA_ERROR =
            "com.bugfuzz.android.projectwalrus.device.CardDeviceManager.EXTRA_ERROR";

    private static final String ACTION_USB_PERMISSION_RESULT =
            "com.bugfuzz.android.projectwalrus.device.CardDeviceManager"
                    + ".ACTION_USB_PERMISSION_RESULT";

    /** The Bluetooth device to reconnect to on start-up and after a dropped link. */
    private static final String PREF_REMEMBERED_BLUETOOTH_ADDRESS =
            "remembered_bluetooth_address";

    private static final long RECONNECT_BASE_DELAY_MS = 2000;
    private static final long RECONNECT_MAX_DELAY_MS = 60000;

    private static final Set<Class<? extends CardDevice>> cardDeviceClasses =
            new HashSet<Class<? extends CardDevice>>(Arrays.asList(
                    Proxmark3Device.class,
                    ChameleonMiniRevGDevice.class,
                    ChameleonMiniRevERebootedDevice.class));

    private final Map<Integer, CardDevice> cardDevices = new LinkedHashMap<>();

    private final Set<UsbDevice> seenUsbDevices =
            Collections.synchronizedSet(new HashSet<UsbDevice>());
    private boolean askingForUsbPermission;

    private final Set<String> connectingBluetoothAddresses =
            Collections.synchronizedSet(new HashSet<String>());
    private long reconnectDelay = RECONNECT_BASE_DELAY_MS;
    private boolean reconnectScheduled;

    public void scanForDevices(Context context) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager != null) {
            for (UsbDevice usbDevice : usbManager.getDeviceList().values()) {
                handleUsbDeviceAttached(context, usbDevice);
            }
        }

        autoConnectRememberedBluetoothDevice(context);
    }

    private synchronized void handleUsbDeviceAttached(Context context, UsbDevice usbDevice) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            return;
        }

        if (askingForUsbPermission || seenUsbDevices.contains(usbDevice)) {
            return;
        }

        seenUsbDevices.add(usbDevice);

        for (Class<? extends CardDevice> klass : cardDeviceClasses) {
            CardDevice.UsbIds usbIds = klass.getAnnotation(CardDevice.UsbIds.class);
            if (usbIds == null) {
                continue;
            }

            for (CardDevice.UsbIds.Ids ids : usbIds.value()) {
                if (ids.vendorId() == usbDevice.getVendorId()
                        && ids.productId() == usbDevice.getProductId()) {
                    if (usbManager.hasPermission(usbDevice)) {
                        new Thread(new CreateUsbDeviceRunnable(context, usbDevice)).start();
                    } else {
                        // Explicit (setClass + setPackage) so Android 14+ still delivers it,
                        // and MUTABLE because UsbManager fills in EXTRA_DEVICE and
                        // EXTRA_PERMISSION_GRANTED on the way out; an immutable PendingIntent
                        // would reach UsbPermissionReceiver with neither extra set.
                        Intent permissionIntent = new Intent(ACTION_USB_PERMISSION_RESULT);
                        permissionIntent.setClass(context, UsbPermissionReceiver.class);
                        permissionIntent.setPackage(context.getPackageName());

                        int flags = 0;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            flags |= PendingIntent.FLAG_MUTABLE;
                        }

                        usbManager.requestPermission(usbDevice, PendingIntent.getBroadcast(
                                context, 0, permissionIntent, flags));

                        askingForUsbPermission = true;
                    }

                    break;
                }
            }
        }
    }

    private void handleUsbDeviceDetached(Context context, UsbDevice usbDevice) {
        Iterator<Map.Entry<Integer, CardDevice>> it = cardDevices.entrySet().iterator();
        while (it.hasNext()) {
            final CardDevice cardDevice = it.next().getValue();

            if (!(cardDevice instanceof SerialCardDevice)) {
                continue;
            }

            SerialCardDevice.Transport transport = ((SerialCardDevice<?>) cardDevice)
                    .getTransport();
            if (!(transport instanceof UsbSerialTransport)) {
                continue;
            }

            if (!((UsbSerialTransport) transport).getUsbDevice().equals(usbDevice)) {
                continue;
            }

            it.remove();

            cardDevice.close();

            broadcastDeviceRemoved(context, cardDevice);

            new Handler(context.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    // noinspection StatementWithEmptyBody
                    while (cardDevices.values().remove(cardDevice)) {
                    }
                }
            });

            break;
        }

        seenUsbDevices.remove(usbDevice);
    }

    private void broadcastDeviceRemoved(Context context, CardDevice cardDevice) {
        Intent broadcastIntent = new Intent(ACTION_UPDATE);
        broadcastIntent.putExtra(EXTRA_DEVICE_WAS_ADDED, false);
        broadcastIntent.putExtra(EXTRA_DEVICE_NAME,
                cardDevice.getClass().getAnnotation(CardDevice.Metadata.class).name());
        LocalBroadcastManager.getInstance(context).sendBroadcast(broadcastIntent);
    }

    private void broadcastDeviceAdded(Context context, CardDevice cardDevice) {
        Intent broadcastIntent = new Intent(ACTION_UPDATE);
        broadcastIntent.putExtra(EXTRA_DEVICE_WAS_ADDED, true);
        broadcastIntent.putExtra(EXTRA_DEVICE_ID, cardDevice.getId());
        broadcastIntent.putExtra(EXTRA_DEVICE_NAME,
                cardDevice.getClass().getAnnotation(CardDevice.Metadata.class).name());
        LocalBroadcastManager.getInstance(context).sendBroadcast(broadcastIntent);
    }

    // ---------------------------------------------------------------- Bluetooth

    /**
     * Which device class, if any, claims a Bluetooth device based on the name its module
     * advertises.
     */
    @Nullable
    @SuppressLint("MissingPermission")
    public static Class<? extends CardDevice> getBluetoothCardDeviceClass(
            BluetoothDevice bluetoothDevice) {
        String name;
        try {
            name = bluetoothDevice.getName();
        } catch (SecurityException e) {
            return null;
        }

        if (name == null) {
            return null;
        }

        String lowerName = name.toLowerCase(Locale.US);

        for (Class<? extends CardDevice> klass : cardDeviceClasses) {
            CardDevice.BluetoothIds ids = klass.getAnnotation(CardDevice.BluetoothIds.class);
            if (ids == null) {
                continue;
            }

            for (String prefix : ids.namePrefixes()) {
                if (lowerName.startsWith(prefix.toLowerCase(Locale.US))) {
                    return klass;
                }
            }
        }

        return onlyBluetoothCardDeviceClass();
    }

    /**
     * People rename these modules, so a name that matches nothing is not a good enough reason to
     * refuse. RFIDtools does not filter on the name at all - the user picked the device from a
     * list, which is the real signal - so fall back to the sole Bluetooth-capable device class
     * when there is exactly one. If the guess is wrong the connection simply fails to open.
     */
    @Nullable
    private static Class<? extends CardDevice> onlyBluetoothCardDeviceClass() {
        Class<? extends CardDevice> only = null;

        for (Class<? extends CardDevice> klass : cardDeviceClasses) {
            if (klass.getAnnotation(CardDevice.BluetoothIds.class) == null) {
                continue;
            }

            if (only != null) {
                return null;
            }

            only = klass;
        }

        return only;
    }

    /**
     * Opens an RFCOMM link to a Bluetooth device and, if it comes up, adds it to the device list
     * and remembers it for future reconnection. Blocks, so must not be called on the main thread.
     */
    @SuppressLint("MissingPermission")
    public void connectBluetoothDevice(Context context, BluetoothDevice bluetoothDevice,
            boolean remember) {
        String address = bluetoothDevice.getAddress();

        if (!connectingBluetoothAddresses.add(address)) {
            return;
        }

        try {
            if (getBluetoothCardDevice(address) != null) {
                broadcastBluetoothResult(context, null);
                return;
            }

            Class<? extends CardDevice> klass = getBluetoothCardDeviceClass(bluetoothDevice);
            if (klass == null) {
                broadcastBluetoothResult(context,
                        context.getString(R.string.bluetooth_device_not_recognised));
                return;
            }

            Constructor<? extends CardDevice> constructor;
            try {
                constructor = klass.getConstructor(Context.class, BluetoothDevice.class);
            } catch (NoSuchMethodException e) {
                broadcastBluetoothResult(context,
                        context.getString(R.string.bluetooth_device_not_recognised));
                return;
            }

            final CardDevice cardDevice;
            try {
                cardDevice = constructor.newInstance(context, bluetoothDevice);
            } catch (InstantiationException | IllegalAccessException e) {
                broadcastBluetoothResult(context,
                        context.getString(R.string.bluetooth_connect_failed));
                return;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                broadcastBluetoothResult(context, cause != null && cause.getMessage() != null
                        ? cause.getMessage()
                        : context.getString(R.string.bluetooth_connect_failed));
                return;
            }

            new Handler(context.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    cardDevices.put(cardDevice.getId(), cardDevice);
                }
            });

            if (remember) {
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .putString(PREF_REMEMBERED_BLUETOOTH_ADDRESS, address)
                        .apply();
            }

            reconnectDelay = RECONNECT_BASE_DELAY_MS;

            broadcastDeviceAdded(context, cardDevice);
            broadcastBluetoothResult(context, null);
        } finally {
            connectingBluetoothAddresses.remove(address);
        }
    }

    private void broadcastBluetoothResult(Context context, @Nullable String error) {
        Intent intent = new Intent(ACTION_BLUETOOTH_CONNECT_RESULT);
        if (error != null) {
            intent.putExtra(EXTRA_ERROR, error);
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    @Nullable
    private CardDevice getBluetoothCardDevice(String address) {
        for (CardDevice cardDevice : cardDevices.values()) {
            if (!(cardDevice instanceof SerialCardDevice)) {
                continue;
            }

            SerialCardDevice.Transport transport = ((SerialCardDevice<?>) cardDevice)
                    .getTransport();
            if (transport instanceof BluetoothSerialTransport
                    && ((BluetoothSerialTransport) transport).getBluetoothDevice().getAddress()
                    .equals(address)) {
                return cardDevice;
            }
        }

        return null;
    }

    /**
     * Forgets the remembered Bluetooth device, so that a dropped link is not chased forever.
     */
    public void forgetBluetoothDevice(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove(PREF_REMEMBERED_BLUETOOTH_ADDRESS)
                .apply();
    }

    @Nullable
    public String getRememberedBluetoothAddress(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_REMEMBERED_BLUETOOTH_ADDRESS, null);
    }

    @SuppressLint("MissingPermission")
    private void autoConnectRememberedBluetoothDevice(final Context context) {
        final String address = getRememberedBluetoothAddress(context);
        if (address == null || getBluetoothCardDevice(address) != null) {
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return;
        }

        final BluetoothDevice bluetoothDevice;
        try {
            bluetoothDevice = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            forgetBluetoothDevice(context);
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                connectBluetoothDevice(context, bluetoothDevice, false);
            }
        }, "walrus-bt-autoconnect").start();
    }

    /**
     * Called by {@link SerialCardDevice} when its pipe drops without a detach broadcast, which is
     * how a Bluetooth link goes away. Drops the device and, if it was the remembered one, starts
     * trying to get it back with a widening delay.
     */
    void onDeviceDisconnected(final Context context, final CardDevice cardDevice) {
        new Handler(context.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                // noinspection StatementWithEmptyBody
                while (cardDevices.values().remove(cardDevice)) {
                }
            }
        });

        broadcastDeviceRemoved(context, cardDevice);

        scheduleBluetoothReconnect(context);
    }

    private synchronized void scheduleBluetoothReconnect(final Context context) {
        if (reconnectScheduled || getRememberedBluetoothAddress(context) == null) {
            return;
        }

        reconnectScheduled = true;

        new Handler(context.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (CardDeviceManager.this) {
                    reconnectScheduled = false;
                }

                String address = getRememberedBluetoothAddress(context);
                if (address == null || getBluetoothCardDevice(address) != null) {
                    // Either the user forgot the device or it is back; stop chasing it.
                    synchronized (CardDeviceManager.this) {
                        reconnectDelay = RECONNECT_BASE_DELAY_MS;
                    }
                    return;
                }

                synchronized (CardDeviceManager.this) {
                    reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_DELAY_MS);
                }

                autoConnectRememberedBluetoothDevice(context);
                scheduleBluetoothReconnect(context);
            }
        }, reconnectDelay);
    }

    // ---------------------------------------------------------------- misc

    public void addDebugDevice(Context context) {
        DebugDevice debugDevice = new DebugDevice(context);
        cardDevices.put(debugDevice.getId(), debugDevice);
    }

    public Map<Integer, CardDevice> getCardDevices() {
        return Collections.unmodifiableMap(cardDevices);
    }

    public static class UsbBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (intent.getAction() == null) {
                return;
            }

            switch (intent.getAction()) {
                case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                case UsbManager.ACTION_USB_DEVICE_DETACHED:
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            UsbDevice usbDevice = intent.getParcelableExtra(
                                    UsbManager.EXTRA_DEVICE);

                            if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                                CardDeviceManager.INSTANCE.handleUsbDeviceAttached(context,
                                        usbDevice);
                            } else {
                                CardDeviceManager.INSTANCE.handleUsbDeviceDetached(context,
                                        usbDevice);
                            }
                        }
                    }).start();
                    break;

                default:
                    break;
            }
        }
    }

    public static class UsbPermissionReceiver extends BroadcastReceiver {
        public void onReceive(final Context context, final Intent intent) {
            CardDeviceManager.INSTANCE.askingForUsbPermission = false;

            UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                new Thread(new CreateUsbDeviceRunnable(context, usbDevice)).start();
            } else {
                CardDeviceManager.INSTANCE.scanForDevices(context);
            }
        }
    }

    private static class CreateUsbDeviceRunnable implements Runnable {

        private final Context context;
        private final UsbDevice usbDevice;

        CreateUsbDeviceRunnable(Context context, UsbDevice usbDevice) {
            this.context = context;
            this.usbDevice = usbDevice;
        }

        @Override
        public void run() {
            for (Class<? extends CardDevice> klass : cardDeviceClasses) {
                CardDevice.UsbIds usbIds = klass.getAnnotation(CardDevice.UsbIds.class);
                if (usbIds == null) {
                    continue;
                }

                for (CardDevice.UsbIds.Ids ids : usbIds.value()) {
                    if (ids.vendorId() == usbDevice.getVendorId()
                            && ids.productId() == usbDevice.getProductId()) {
                        Constructor<? extends CardDevice> constructor;
                        try {
                            constructor = klass.getConstructor(Context.class, UsbDevice.class);
                        } catch (NoSuchMethodException e) {
                            continue;
                        }

                        final CardDevice cardDevice;
                        try {
                            cardDevice = constructor.newInstance(context, usbDevice);
                        } catch (InstantiationException | InvocationTargetException
                                | IllegalAccessException e) {
                            continue;
                        }

                        new Handler(context.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                CardDeviceManager.INSTANCE.cardDevices.put(cardDevice.getId(),
                                        cardDevice);
                            }
                        });

                        CardDeviceManager.INSTANCE.broadcastDeviceAdded(context, cardDevice);
                    }
                }
            }

            CardDeviceManager.INSTANCE.scanForDevices(context);
        }
    }
}
