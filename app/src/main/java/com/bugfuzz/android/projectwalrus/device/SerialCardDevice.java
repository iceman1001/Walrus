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
import android.util.Pair;

import com.bugfuzz.android.projectwalrus.util.MiscUtils;

import org.apache.commons.lang3.ArrayUtils;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * A card device that talks a byte-stream protocol, with the framing kept separate from the pipe
 * the bytes travel over.
 *
 * <p>This used to be {@code UsbSerialCardDevice} with the USB serial port built in. It was split
 * apart so that the same device implementation can be reached over USB or over a Bluetooth
 * RFCOMM socket: the Proxmark3's Blue Shark add-on hangs off the same UART the firmware already
 * speaks its protocol on, so nothing above {@link Transport} needs to know which one is in use.
 */
public abstract class SerialCardDevice<T> extends CardDevice {

    private final BlockingQueue<T> receiveQueue = new LinkedBlockingQueue<>();

    private final Transport transport;

    private volatile boolean receiving;
    private byte[] buffer = new byte[0];

    protected SerialCardDevice(Context context, Transport transport, String status)
            throws IOException {
        super(context, status);

        this.transport = transport;

        transport.open(new Transport.Listener() {
            @Override
            public void onBytesReceived(byte[] in) {
                SerialCardDevice.this.onBytesReceived(in);
            }

            @Override
            public void onTransportClosed(IOException reason) {
                SerialCardDevice.this.onTransportClosed(reason);
            }
        });
    }

    public Transport getTransport() {
        return transport;
    }

    private void onBytesReceived(byte[] in) {
        buffer = ArrayUtils.addAll(buffer, in);

        for (; ; ) {
            Pair<T, Integer> sliced = sliceIncoming(buffer);
            if (sliced == null) {
                break;
            }

            Logger.getAnonymousLogger().info("<<< sliced: " + sliced.first + " - "
                    + MiscUtils.bytesToHex(buffer, false));

            buffer = ArrayUtils.subarray(buffer, sliced.second, buffer.length);

            if (receiving) {
                // CHECKSTYLE:OFF EmptyCatchBlock
                try {
                    receiveQueue.put(sliced.first);
                } catch (InterruptedException ignored) {
                }
                // CHECKSTYLE:ON EmptyCatchBlock
            }
        }
    }

    /**
     * Called when the pipe drops of its own accord, which a Bluetooth link does whenever the
     * device goes out of range or is switched off. USB has its own detach broadcast instead.
     */
    protected void onTransportClosed(IOException reason) {
        CardDeviceManager.INSTANCE.onDeviceDisconnected(context, this);
    }

    @Override
    public void close() {
        transport.close();

        super.close();
    }

    protected void setReceiving(boolean receiving) {
        if (receiving) {
            receiveQueue.clear();
        }

        this.receiving = receiving;
    }

    protected abstract Pair<T, Integer> sliceIncoming(byte[] in);

    protected abstract byte[] formatOutgoing(T out);

    protected T receive(long timeout) {
        if (!receiving) {
            throw new RuntimeException("Not receiving");
        }

        try {
            return receiveQueue.poll(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }

    private <O> O receive(ReceiveSink<T, O> receiveSink,
            @SuppressWarnings("SameParameterValue") long internalTimeout)
            throws IOException {
        while (receiveSink.wantsMore()) {
            T in = receive(internalTimeout);
            if (in == null) {
                continue;
            }

            O result = receiveSink.onReceived(in);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    protected <O> O receive(ReceiveSink<T, O> receiveSink) throws IOException {
        return receive(receiveSink, 250);
    }

    protected void send(T out) {
        byte[] bytes = formatOutgoing(out);
        if (bytes == null) {
            throw new RuntimeException("Failed to format outgoing");
        }

        Logger.getAnonymousLogger().info(">>> wrote: " + new String(bytes) + " - "
                + MiscUtils.bytesToHex(bytes, false));
        transport.write(bytes);
    }

    /**
     * The byte pipe underneath a {@link SerialCardDevice}. Implementations are expected to be
     * usable from any thread and to deliver reads on a thread of their own choosing.
     */
    public interface Transport {

        void open(Listener listener) throws IOException;

        void write(byte[] bytes);

        void close();

        /** Something the user can be shown to identify the connection, e.g. a MAC address. */
        String getDescription();

        interface Listener {
            void onBytesReceived(byte[] in);

            void onTransportClosed(IOException reason);
        }
    }

    protected abstract static class ReceiveSink<T, O> {
        public abstract O onReceived(T in) throws IOException;

        public boolean wantsMore() {
            return true;
        }
    }

    protected abstract static class WatchdogReceiveSink<T, O> extends ReceiveSink<T, O> {

        private final long timeout;
        private long lastWatchdogReset;

        public WatchdogReceiveSink(long timeout) {
            this.timeout = timeout;

            resetWatchdog();
        }

        protected void resetWatchdog() {
            lastWatchdogReset = System.currentTimeMillis();
        }

        public boolean wantsMore() {
            return System.currentTimeMillis() < lastWatchdogReset + timeout;
        }
    }
}
