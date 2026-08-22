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

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.bugfuzz.android.projectwalrus.R;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.io.IOException;

/**
 * A {@link SerialCardDevice.Transport} over a USB CDC/FTDI style serial port, which is what every
 * card device Walrus supports has always used.
 */
public class UsbSerialTransport implements SerialCardDevice.Transport {

    private final Context context;
    private final UsbDevice usbDevice;
    private final int baudRate;

    private UsbDeviceConnection usbDeviceConnection;
    private UsbSerialDevice usbSerialDevice;

    public UsbSerialTransport(Context context, UsbDevice usbDevice, int baudRate) {
        this.context = context;
        this.usbDevice = usbDevice;
        this.baudRate = baudRate;
    }

    public UsbDevice getUsbDevice() {
        return usbDevice;
    }

    @Override
    public void open(final Listener listener) throws IOException {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            throw new IOException(context.getString(R.string.failed_open_usb_connection));
        }

        usbDeviceConnection = usbManager.openDevice(usbDevice);
        if (usbDeviceConnection == null) {
            throw new IOException(context.getString(R.string.failed_open_usb_connection));
        }

        usbSerialDevice = UsbSerialDevice.createUsbSerialDevice(usbDevice, usbDeviceConnection);
        if (!usbSerialDevice.open()) {
            usbDeviceConnection.close();
            usbDeviceConnection = null;
            throw new IOException(context.getString(R.string.failed_open_usb_serial_device));
        }

        usbSerialDevice.setBaudRate(baudRate);
        usbSerialDevice.setDataBits(UsbSerialInterface.DATA_BITS_8);
        usbSerialDevice.setParity(UsbSerialInterface.PARITY_NONE);
        usbSerialDevice.setStopBits(UsbSerialInterface.STOP_BITS_1);
        usbSerialDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);

        usbSerialDevice.read(new UsbSerialInterface.UsbReadCallback() {
            @Override
            public void onReceivedData(byte[] in) {
                listener.onBytesReceived(in);
            }
        });
    }

    @Override
    public void write(byte[] bytes) {
        if (usbSerialDevice != null) {
            usbSerialDevice.write(bytes);
        }
    }

    @Override
    public void close() {
        if (usbSerialDevice != null) {
            usbSerialDevice.close();
            usbSerialDevice = null;
        }

        if (usbDeviceConnection != null) {
            usbDeviceConnection.close();
            usbDeviceConnection = null;
        }
    }

    @Override
    public String getDescription() {
        return usbDevice.getDeviceName();
    }
}
