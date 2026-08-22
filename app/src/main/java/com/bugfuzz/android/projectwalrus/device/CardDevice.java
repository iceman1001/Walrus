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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AppCompatActivity;

import com.bugfuzz.android.projectwalrus.card.carddata.CardData;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public abstract class CardDevice {

    public static final String ACTION_STATUS_UPDATE =
            "com.bugfuzz.android.projectwalrus.device.CardDevice.ACTION_STATUS_UPDATE";

    private static final String EXTRA_DEVICE_ID =
            "com.bugfuzz.android.projectwalrus.device.CardDevice.EXTRA_DEVICE_ID";
    private static final String EXTRA_STATUS =
            "com.bugfuzz.android.projectwalrus.device.CardDevice.EXTRA_STATUS";

    private static int nextId;

    protected final Context context;

    private final int id;

    private String status;

    CardDevice(Context context, String status) {
        this.context = context;

        id = nextId++;

        setStatus(status);
    }

    @UiThread
    public abstract void createReadCardDataOperation(AppCompatActivity activity,
            Class<? extends CardData> cardDataClass, int callbackId);

    @UiThread
    public abstract void createWriteOrEmulateDataOperation(AppCompatActivity activity,
            CardData cardData, boolean write, int callbackId);

    // TODO: use LiveData instead (and elsewhere)?
    protected void setStatus(String status) {
        this.status = status;

        Intent broadcastIntent = new Intent(ACTION_STATUS_UPDATE);
        broadcastIntent.putExtra(EXTRA_DEVICE_ID, getId());
        broadcastIntent.putExtra(EXTRA_STATUS, status);
        LocalBroadcastManager.getInstance(context).sendBroadcast(broadcastIntent);
    }

    public String getStatusText() {
        return status;
    }

    @Nullable
    public Intent getDeviceActivityIntent(Context context) {
        return null;
    }

    void close() {
    }

    public int getId() {
        return id;
    }

    protected void ensureOperationCreatedCallbackSupported(Activity activity) {
        if (!(activity instanceof OnOperationCreatedCallback)) {
            throw new IllegalArgumentException("Activity doesn't implement operation creation "
                    + "callback interface");
        }
    }

    public interface OnOperationCreatedCallback {
        @UiThread
        void onOperationCreated(CardDeviceOperation operation, int callbackId);
    }

    // TODO: this should really be treated as any other async operation
    public interface Versioned {
        String getVersion() throws IOException;
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Metadata {
        String name();

        @DrawableRes int iconId();

        Class<? extends CardData>[] supportsRead();

        Class<? extends CardData>[] supportsWrite();

        Class<? extends CardData>[] supportsEmulate();
    }

    /**
     * The USB vendor/product pairs a device class answers to. Moved here from UsbCardDevice when
     * the transport was split out, so that one device class can be reachable over more than one
     * kind of connection.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface UsbIds {
        Ids[] value();

        @Target({})
        @Retention(RetentionPolicy.RUNTIME)
        @interface Ids {
            int vendorId();

            int productId();
        }
    }

    /**
     * Bluetooth has no equivalent of a USB vendor/product id, so devices are matched on the name
     * their module advertises. The Proxmark3 Blue Shark ships as "PM3_RDV4.0"; see section 6.1 of
     * doc/bt_manual_v10.md in the Iceman fork.
     *
     * <p>A class carrying this annotation must also offer a
     * {@code (Context, android.bluetooth.BluetoothDevice)} constructor.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface BluetoothIds {
        /** Matched case-insensitively against the start of the remote device's name. */
        String[] namePrefixes();
    }
}
