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

package com.bugfuzz.android.projectwalrus.device.proxmark3;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;

import com.bugfuzz.android.projectwalrus.R;
import com.bugfuzz.android.projectwalrus.card.carddata.CardData;
import com.bugfuzz.android.projectwalrus.card.carddata.HIDCardData;
import com.bugfuzz.android.projectwalrus.card.carddata.ISO14443ACardData;
import com.bugfuzz.android.projectwalrus.card.carddata.MifareCardData;
import com.bugfuzz.android.projectwalrus.card.carddata.MifareReadStep;
import com.bugfuzz.android.projectwalrus.card.carddata.ui.MifareReadSetupDialogFragment;
import com.bugfuzz.android.projectwalrus.device.CardDevice;
import com.bugfuzz.android.projectwalrus.device.ReadCardDataOperation;
import com.bugfuzz.android.projectwalrus.device.BluetoothSerialTransport;
import com.bugfuzz.android.projectwalrus.device.SerialCardDevice;
import com.bugfuzz.android.projectwalrus.device.UsbSerialTransport;
import com.bugfuzz.android.projectwalrus.device.WriteOrEmulateCardDataOperation;
import com.bugfuzz.android.projectwalrus.device.proxmark3.ui.Proxmark3Activity;
import org.apache.commons.lang3.ArrayUtils;
import org.parceler.Parcel;
import org.parceler.ParcelConstructor;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// TODO: do periodic VERSION-based device-aliveness checking like Chameleon Mini will/does

/**
 * Speaks the Proxmark3 NG protocol (see {@link Proxmark3CommandNG}).
 *
 * <p>This used to be split into a legacy 544-byte {@code PacketCommandOLD} implementation and a
 * separate NG one for the Iceman fork. That split no longer buys anything: current firmware still
 * accepts legacy commands, but every response it sends goes through {@code reply_ng_internal()}
 * and is NG-framed, so a legacy-only client can talk but never listen.
 */
@CardDevice.Metadata(
        name = "Proxmark3",
        iconId = R.drawable.drawable_proxmark3,
        supportsRead = {HIDCardData.class, MifareCardData.class},
        supportsWrite = {HIDCardData.class},
        supportsEmulate = {}
)
@CardDevice.UsbIds({
        @CardDevice.UsbIds.Ids(vendorId = 0x2d2d, productId = 0x504d), // CDC Proxmark3
        @CardDevice.UsbIds.Ids(vendorId = 0x9ac4, productId = 0x4b8f), // HID Proxmark3
        @CardDevice.UsbIds.Ids(vendorId = 0x502d, productId = 0x502d)  // Proxmark3 Easy(?)
})
// The Blue Shark add-on ships advertising "PM3_RDV4.0"; the plain "PM3" prefix covers the
// aftermarket HC-05/HC-06 modules people rename themselves.
@CardDevice.BluetoothIds(namePrefixes = {"PM3", "Proxmark"})
public class Proxmark3Device extends SerialCardDevice<Proxmark3CommandNG>
        implements CardDevice.Versioned, MifareReadStep.BlockSource {

    private static final String TAG = "Proxmark3Device";

    private static final long DEFAULT_TIMEOUT = 20 * 1000;

    /**
     * Firmware old enough not to know CMD_CAPABILITIES never answers it, so this must not be the
     * usual 20s. The desktop client allows 1s (client/src/comms.c); allow a little more for BT.
     */
    private static final long CAPABILITIES_TIMEOUT = 3 * 1000;

    /** The client allows 1s per live sample; be a little more forgiving over BT. */
    private static final long LIVE_TUNE_TIMEOUT = 3 * 1000;

    /** Both the USB CDC port and the Blue Shark UART run at this rate. */
    private static final int BAUD_RATE = 115200;

    /**
     * lf_hid_watch() in armsrc/lfops.c reports a decoded tag as a debug string, not in the reply
     * to CMD_LF_HID_WATCH, which carries a status and nothing else. The colour escapes it wraps
     * the value in are stripped by {@link Proxmark3CommandNG#debugString()} before this matches.
     *
     * <p>The two shapes it emits are
     * {@code TAG ID: %x%08x (%d) - Format Len: %d bit - FC: %d - Card: %d} for standard tags and
     * {@code TAG ID: %x%08x%08x (%d)} for the 88/192 bit ones.
     */
    private static final Pattern TAG_ID = Pattern.compile("TAG ID: ([0-9a-fA-F]+)");

    private final Semaphore semaphore = new Semaphore(1);

    /** Fetched once by {@link #getCapabilities()} and kept for the life of the connection. */
    @Nullable
    private volatile Pm3Capabilities capabilities;

    @Keep
    public Proxmark3Device(Context context, UsbDevice usbDevice) throws IOException {
        this(context, new UsbSerialTransport(context, usbDevice, BAUD_RATE));
    }

    @Keep
    public Proxmark3Device(Context context, BluetoothDevice bluetoothDevice) throws IOException {
        this(context, new BluetoothSerialTransport(context, bluetoothDevice));
    }

    private Proxmark3Device(Context context, Transport transport) throws IOException {
        super(context, transport, context.getString(R.string.idle));

        send(Proxmark3CommandNG.ng(Proxmark3CommandNG.VERSION));
    }

    @Override
    protected Pair<Proxmark3CommandNG, Integer> sliceIncoming(byte[] in) {
        if (in.length < Proxmark3CommandNG.RESPONSE_PREAMBLE_SIZE) {
            return null;
        }

        int magic = ByteBuffer.wrap(in, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (magic != Proxmark3CommandNG.RESPONSENG_PREAMBLE_MAGIC) {
            // Resynchronise: drop everything up to the next plausible frame start. The sinks all
            // filter on the opcode, so the placeholder is simply ignored by whoever is listening.
            int next = indexOfMagic(in, 1);
            if (next == -1) {
                // Keep the last few bytes, which may be a magic split across two reads.
                int keep = Math.min(in.length, 3);
                return new Pair<>(Proxmark3CommandNG.unknown(), in.length - keep);
            }

            return new Pair<>(Proxmark3CommandNG.unknown(), next);
        }

        Proxmark3CommandNG response = Proxmark3CommandNG.responseFromBytes(in);
        if (response == null) {
            return null;
        }

        return new Pair<>(response, response.getResponseByteLength());
    }

    private static int indexOfMagic(byte[] in, int from) {
        for (int i = from; i + 4 <= in.length; ++i) {
            if (ByteBuffer.wrap(in, i, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                    == Proxmark3CommandNG.RESPONSENG_PREAMBLE_MAGIC) {
                return i;
            }
        }

        return -1;
    }

    @Override
    protected byte[] formatOutgoing(Proxmark3CommandNG out) {
        return out.toBytes();
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean tryAcquireAndSetStatus(String status) {
        if (!semaphore.tryAcquire()) {
            return false;
        }

        setStatus(status);

        return true;
    }

    private void releaseAndSetStatus() {
        setStatus(context.getString(R.string.idle));
        semaphore.release();
    }

    private <O> O sendThenReceiveCommands(Proxmark3CommandNG out,
            ReceiveSink<Proxmark3CommandNG, O> receiveSink) throws IOException {
        setReceiving(true);

        try {
            send(out);
            return receive(receiveSink);
        } finally {
            setReceiving(false);
        }
    }

    @Override
    @UiThread
    public void createReadCardDataOperation(final AppCompatActivity activity,
            Class<? extends CardData> cardDataClass, final int callbackId) {
        ensureOperationCreatedCallbackSupported(activity);

        if (cardDataClass == HIDCardData.class) {
            ((OnOperationCreatedCallback) activity).onOperationCreated(new ReadHIDOperation(this),
                    callbackId);
        } else if (cardDataClass == MifareCardData.class) {
            MifareReadSetupDialogFragment dialog = MifareReadSetupDialogFragment.create(callbackId);

            dialog.show(activity.getSupportFragmentManager(),
                    "proxmark3_device_mifare_read_setup_dialog");
            activity.getSupportFragmentManager().executePendingTransactions();

            dialog.getViewModel().getSelectedReadSteps().observeForever(
                    new Observer<List<MifareReadStep>>() {
                        @Override
                        public void onChanged(@Nullable List<MifareReadStep> readSteps) {
                            ((OnOperationCreatedCallback) activity).onOperationCreated(
                                    new ReadMifareOperation(Proxmark3Device.this, readSteps),
                                    callbackId);
                        }
                    });
        } else {
            throw new RuntimeException("Invalid card data class");
        }
    }

    @Override
    public MifareCardData.Block readMifareBlock(int blockNumber, MifareCardData.Key key,
            MifareCardData.KeySlot keySlot) throws IOException {
        byte[] payload = Pm3MfReadBlock.toBytes(blockNumber,
                keySlot == MifareCardData.KeySlot.A
                        ? Pm3MfReadBlock.KEY_TYPE_A
                        : Pm3MfReadBlock.KEY_TYPE_B,
                key.key);

        Pair<Boolean, MifareCardData.Block> result = sendThenReceiveCommands(
                Proxmark3CommandNG.ng(Proxmark3CommandNG.HF_MIFARE_READBL, payload),
                new ReceiveSink<Proxmark3CommandNG, Pair<Boolean, MifareCardData.Block>>() {
                    @Override
                    public Pair<Boolean, MifareCardData.Block> onReceived(
                            Proxmark3CommandNG in) {
                        if (in.cmd != Proxmark3CommandNG.HF_MIFARE_READBL) {
                            return null;
                        }

                        // The firmware replies on the same opcode, carrying the return value of
                        // mifare_cmd_readblocks() as the NG status and the block as the payload.
                        if (in.status != Proxmark3CommandNG.PM3_SUCCESS
                                || in.data.length < MifareCardData.Block.SIZE) {
                            return new Pair<>(false, null);
                        }

                        return new Pair<>(true, new MifareCardData.Block(ArrayUtils.subarray(
                                in.data, 0, MifareCardData.Block.SIZE)));
                    }
                });

        return result != null ? result.second : null;
    }

    @Override
    @UiThread
    public void createWriteOrEmulateDataOperation(AppCompatActivity activity, CardData cardData,
            boolean write, int callbackId) {
        ensureOperationCreatedCallbackSupported(activity);

        ((OnOperationCreatedCallback) activity).onOperationCreated(
                new WriteOrEmulateHIDOperation(this, cardData, write), callbackId);
    }

    @Override
    public Intent getDeviceActivityIntent(Context context) {
        return Proxmark3Activity.getStartActivityIntent(context, this);
    }

    @Override
    public String getVersion() throws IOException {
        if (!tryAcquireAndSetStatus(context.getString(R.string.getting_version))) {
            throw new IOException(context.getString(R.string.device_busy));
        }

        try {
            Proxmark3CommandNG version = sendThenReceiveCommands(
                    Proxmark3CommandNG.ng(Proxmark3CommandNG.VERSION),
                    new CommandWaiter(Proxmark3CommandNG.VERSION, DEFAULT_TIMEOUT));
            if (version == null) {
                throw new IOException(context.getString(R.string.get_version_timeout));
            }

            Pm3VersionInfo versionInfo = Pm3VersionInfo.parse(version.data);
            if (versionInfo == null) {
                throw new IOException(context.getString(R.string.get_version_timeout));
            }

            return versionInfo.getVersionString();
        } finally {
            releaseAndSetStatus();
        }
    }

    /**
     * What this particular board can do, from {@link Proxmark3CommandNG#CAPABILITIES}. Fetched on
     * first use and cached: it cannot change while the device stays connected.
     *
     * <p>Deliberately not fetched from the constructor, which runs on the thread that enumerated
     * the device and must not block on a round trip.
     *
     * <p>Only throws if the device is busy with something else. A device that does not answer, or
     * that answers with a struct version this build does not know, yields
     * {@link Pm3Capabilities#baseline()} rather than an error: refusing to talk to a Proxmark3
     * because its firmware is unfamiliar would be worse than assuming the basics.
     */
    public Pm3Capabilities getCapabilities() throws IOException {
        Pm3Capabilities cached = capabilities;
        if (cached != null) {
            return cached;
        }

        if (!tryAcquireAndSetStatus(context.getString(R.string.getting_capabilities))) {
            throw new IOException(context.getString(R.string.device_busy));
        }

        try {
            Proxmark3CommandNG response = sendThenReceiveCommands(
                    Proxmark3CommandNG.ng(Proxmark3CommandNG.CAPABILITIES),
                    new CommandWaiter(Proxmark3CommandNG.CAPABILITIES, CAPABILITIES_TIMEOUT));

            Pm3Capabilities parsed = response != null
                    ? Pm3Capabilities.parse(response.data)
                    : null;
            if (parsed == null) {
                parsed = Pm3Capabilities.baseline();
            }

            // Logged rather than shown: the flag bit ordering still wants confirming against real
            // hardware, and this is what that check reads. See PM3-PM5-DESIGN.md section 5.
            Log.d(TAG, "Capabilities: " + parsed);

            capabilities = parsed;
            return parsed;
        } finally {
            releaseAndSetStatus();
        }
    }

    public TuneResult tune(boolean lf, boolean hf) throws IOException {
        if (!tryAcquireAndSetStatus(context.getString(R.string.tuning))) {
            throw new IOException(context.getString(R.string.device_busy));
        }

        try {
            if (!lf && !hf) {
                throw new IllegalArgumentException("Must tune LF or HF");
            }

            // CMD_MEASURE_ANTENNA_TUNING takes no payload any more and always sweeps both bands;
            // the old MEASURE_ANTENNA_TUNING_FLAG_TUNE_* args are gone, as is the separate
            // MEASURED_ANTENNA_TUNING (0x410) reply opcode. The result now comes back as an NG
            // response on the same opcode.
            Proxmark3CommandNG result = sendThenReceiveCommands(
                    Proxmark3CommandNG.ng(Proxmark3CommandNG.MEASURE_ANTENNA_TUNING),
                    new CommandWaiter(Proxmark3CommandNG.MEASURE_ANTENNA_TUNING, DEFAULT_TIMEOUT));
            if (result == null) {
                throw new IOException(context.getString(R.string.tune_timeout));
            }

            Pm3AntennaTuning tuning = Pm3AntennaTuning.parse(result.data);
            if (tuning == null) {
                throw new IOException(context.getString(R.string.tune_timeout));
            }

            long[] sweep = tuning.getSweepMillivolts();
            float[] lfVoltages = new float[sweep.length];
            for (int i = 0; i < sweep.length; ++i) {
                lfVoltages[i] = sweep[i] / 1e3f;
            }

            return new TuneResult(
                    lf, hf,
                    lf ? lfVoltages : null,
                    lf ? tuning.getVoltageLf125() / 1e3f : null,
                    lf ? tuning.getVoltageLf134() / 1e3f : null,
                    lf ? tuning.getPeakFrequency() : null,
                    lf ? tuning.getPeakVoltage() / 1e3f : null,
                    hf ? tuning.getVoltageHf() / 1e3f : null);
        } finally {
            releaseAndSetStatus();
        }
    }

    /**
     * The continuous measurement behind the client's {@code lf tune} and {@code hf tune}, which
     * is a different thing from {@code hw tune}: rather than sweeping the LF band once, the
     * firmware parks the field at one frequency and hands back a fresh reading each time it is
     * asked, until told to stop.
     *
     * <p>Protocol, from client/src/cmdlf.c and cmdhf.c: send mode 1 to start, mode 2 repeatedly
     * to sample, mode 3 to shut the field down. LF carries {mode, divisor} and the firmware
     * rejects anything that is not exactly two bytes; HF carries {mode} and insists on one. A
     * reply with status PM3_EOPABORTED means the button on the Proxmark3 was pressed.
     *
     * <p>Blocks until the sink stops wanting more, so call it off the main thread.
     */
    public void liveTune(boolean lf, LiveTuneSink sink) throws IOException {
        if (!tryAcquireAndSetStatus(context.getString(R.string.tuning))) {
            throw new IOException(context.getString(R.string.device_busy));
        }

        try {
            int op = lf ? Proxmark3CommandNG.MEASURE_ANTENNA_TUNING_LF
                    : Proxmark3CommandNG.MEASURE_ANTENNA_TUNING_HF;

            if (sendThenReceiveCommands(
                    Proxmark3CommandNG.ng(op, Pm3TuneMode.toBytes(lf,
                            Proxmark3CommandNG.TUNE_MODE_START)),
                    new CommandWaiter(op, DEFAULT_TIMEOUT)) == null) {
                throw new IOException(context.getString(R.string.tune_timeout));
            }

            try {
                byte[] read = Pm3TuneMode.toBytes(lf, Proxmark3CommandNG.TUNE_MODE_READ);

                while (sink.wantsMore()) {
                    Proxmark3CommandNG result = sendThenReceiveCommands(
                            Proxmark3CommandNG.ng(op, read),
                            new CommandWaiter(op, LIVE_TUNE_TIMEOUT));
                    if (result == null) {
                        throw new IOException(context.getString(R.string.tune_timeout));
                    }

                    if (result.status == Proxmark3CommandNG.PM3_EOPABORTED) {
                        // The button on the device was pressed.
                        break;
                    }

                    Long millivolts = Pm3TuneMode.parseVoltage(result.data);
                    if (millivolts == null) {
                        break;
                    }

                    sink.onVoltage(millivolts);
                }
            } finally {
                sendThenReceiveCommands(
                        Proxmark3CommandNG.ng(op, Pm3TuneMode.toBytes(lf,
                                Proxmark3CommandNG.TUNE_MODE_STOP)),
                        new CommandWaiter(op, DEFAULT_TIMEOUT));
            }
        } finally {
            releaseAndSetStatus();
        }
    }

    /** The frequency the live LF measurement parks at, in Hz. */
    public static float getLiveTuneLfFrequency() {
        return Pm3AntennaTuning.divisorToFrequency(Proxmark3CommandNG.LF_DIVISOR_125);
    }

    public interface LiveTuneSink {
        boolean wantsMore();

        void onVoltage(long millivolts);
    }

    private static class ReadHIDOperation extends ReadCardDataOperation {

        ReadHIDOperation(CardDevice cardDevice) {
            super(cardDevice);
        }

        @Override
        @WorkerThread
        public void execute(Context context, final ShouldContinueCallback shouldContinueCallback,
                final ResultSink resultSink) throws IOException {
            Proxmark3Device proxmark3Device = (Proxmark3Device) getCardDeviceOrThrow();

            if (!proxmark3Device.tryAcquireAndSetStatus(context.getString(R.string.reading))) {
                throw new IOException(context.getString(R.string.device_busy));
            }

            try {
                proxmark3Device.setReceiving(true);

                try {
                    proxmark3Device.send(
                            Proxmark3CommandNG.ng(Proxmark3CommandNG.LF_HID_WATCH));

                    proxmark3Device.receive(new ReceiveSink<Proxmark3CommandNG, Boolean>() {
                        @Override
                        public Boolean onReceived(Proxmark3CommandNG in) {
                            // The watch loop finishes by replying on its own opcode with a status
                            // and no payload; it no longer prints "Stopped".
                            if (in.cmd == Proxmark3CommandNG.LF_HID_WATCH) {
                                return true;
                            }

                            if (in.cmd != Proxmark3CommandNG.DEBUG_PRINT_STRING) {
                                return null;
                            }

                            Matcher matcher = TAG_ID.matcher(in.debugString());
                            if (matcher.find()) {
                                resultSink.onResult(new HIDCardData(new BigInteger(
                                        matcher.group(1), 16)));
                            }

                            return null;
                        }

                        @Override
                        public boolean wantsMore() {
                            return shouldContinueCallback.shouldContinue();
                        }
                    });
                } finally {
                    proxmark3Device.setReceiving(false);
                }

                // lf_hid_watch() breaks out of its loop as soon as anything arrives from the
                // host (data_available()), so any command will stop it. Ping is the cheapest.
                proxmark3Device.send(Proxmark3CommandNG.ng(Proxmark3CommandNG.PING));
            } finally {
                proxmark3Device.releaseAndSetStatus();
            }
        }

        @Override
        public Class<? extends CardData> getCardDataClass() {
            return HIDCardData.class;
        }
    }

    private static class WriteOrEmulateHIDOperation extends WriteOrEmulateCardDataOperation {

        WriteOrEmulateHIDOperation(CardDevice cardDevice, CardData cardData, boolean write) {
            super(cardDevice, cardData, write);
        }

        @Override
        @WorkerThread
        public void execute(Context context, ShouldContinueCallback shouldContinueCallback)
                throws IOException {
            if (!isWrite()) {
                throw new RuntimeException("Can't emulate");
            }

            Proxmark3Device proxmark3Device = (Proxmark3Device) getCardDeviceOrThrow();

            if (!proxmark3Device.tryAcquireAndSetStatus(context.getString(R.string.writing))) {
                throw new IOException(context.getString(R.string.device_busy));
            }

            try {
                HIDCardData hidCardData = (HIDCardData) getCardData();

                byte[] payload = Pm3LfHidSim.toBytes(hidCardData.data, false, false);

                if (!proxmark3Device.sendThenReceiveCommands(
                        Proxmark3CommandNG.ng(Proxmark3CommandNG.LF_HID_CLONE, payload),
                        new WatchdogReceiveSink<Proxmark3CommandNG, Boolean>(DEFAULT_TIMEOUT) {
                            @Override
                            public Boolean onReceived(Proxmark3CommandNG in) {
                                if (in.cmd == Proxmark3CommandNG.WTX) {
                                    resetWatchdog();
                                    return null;
                                }

                                // CopyHIDtoT55x7() finishes with reply_ng(CMD_LF_HID_CLONE, ...);
                                // it no longer prints "DONE!".
                                return in.cmd == Proxmark3CommandNG.LF_HID_CLONE
                                        && in.status == Proxmark3CommandNG.PM3_SUCCESS
                                        ? true : null;
                            }
                        })) {
                    throw new IOException(context.getString(R.string.write_card_timeout));
                }
            } finally {
                proxmark3Device.releaseAndSetStatus();
            }
        }
    }

    private static class ReadMifareOperation extends ReadCardDataOperation {

        private final List<MifareReadStep> readSteps;

        ReadMifareOperation(CardDevice cardDevice, List<MifareReadStep> readSteps) {
            super(cardDevice);

            this.readSteps = readSteps;
        }

        @Override
        @WorkerThread
        public void execute(Context context, ShouldContinueCallback shouldContinueCallback,
                ResultSink resultSink) throws IOException {
            Proxmark3Device proxmark3Device = (Proxmark3Device) getCardDeviceOrThrow();

            if (!proxmark3Device.tryAcquireAndSetStatus(context.getString(R.string.reading))) {
                throw new IOException(context.getString(R.string.device_busy));
            }

            try {
                ISO14443ACardData lastIso14443APart = null;

                while (shouldContinueCallback.shouldContinue()) {
                    // TODO: configurable ratelimiting?
                    // ReaderIso14443a() still reads its flags out of oldarg[0] and still answers
                    // with reply_mix(CMD_ACK, ...), so this one stays a MIX frame.
                    Proxmark3CommandNG result = proxmark3Device.sendThenReceiveCommands(
                            Proxmark3CommandNG.mix(Proxmark3CommandNG.HF_ISO14443A_READER,
                                    new long[]{Proxmark3CommandNG.ISO14A_CONNECT, 0, 0},
                                    new byte[0]),
                            new CommandWaiter(Proxmark3CommandNG.ACK, DEFAULT_TIMEOUT));

                    if (result == null) {
                        break;
                    }

                    if (result.oldargs[0] == 0) {
                        continue;
                    }

                    Pm3Iso14aCardSelect cardSelect = Pm3Iso14aCardSelect.parse(result.data);
                    if (cardSelect == null) {
                        continue;
                    }

                    ISO14443ACardData iso14443APart = new ISO14443ACardData();
                    iso14443APart.uid = cardSelect.getUid();
                    iso14443APart.atqa = cardSelect.getAtqa();
                    iso14443APart.sak = cardSelect.getSak();
                    iso14443APart.ats = cardSelect.getAts();

                    if (!iso14443APart.equals(lastIso14443APart)) {
                        MifareCardData mifareCardData = new MifareCardData(iso14443APart, null);

                        int i = 1;
                        for (MifareReadStep readStep : readSteps) {
                            if (!shouldContinueCallback.shouldContinue()) {
                                break;
                            }

                            proxmark3Device.setStatus("Reading - step #" + i++ + " of "
                                    + readSteps.size());

                            readStep.execute(mifareCardData, proxmark3Device,
                                    shouldContinueCallback);
                        }

                        proxmark3Device.setStatus(context.getString(R.string.reading));

                        resultSink.onResult(mifareCardData);
                    }

                    lastIso14443APart = iso14443APart;
                }
            } finally {
                proxmark3Device.releaseAndSetStatus();
            }
        }

        @Override
        public Class<? extends CardData> getCardDataClass() {
            return MifareCardData.class;
        }
    }

    private static class CommandWaiter
            extends WatchdogReceiveSink<Proxmark3CommandNG, Proxmark3CommandNG> {

        @Proxmark3CommandNG.Opcode
        private final int cmd;

        CommandWaiter(@Proxmark3CommandNG.Opcode int cmd,
                @SuppressWarnings("SameParameterValue") long timeout) {
            super(timeout);

            this.cmd = cmd;
        }

        @Override
        public Proxmark3CommandNG onReceived(Proxmark3CommandNG in) {
            // A slow operation asks for more time rather than going quiet: an antenna tune sends
            // two of these before its result. The real client adds the requested milliseconds to
            // its timeout (client/src/comms.c); restarting the watchdog has the same effect.
            if (in.cmd == Proxmark3CommandNG.WTX) {
                resetWatchdog();
                return null;
            }

            return in.cmd == cmd ? in : null;
        }
    }

    @Parcel
    public static class TuneResult {

        public final boolean lf;
        public final boolean hf;
        public final float[] lfVoltages;
        public final Float v125;
        public final Float v134;
        public final Float peakF;
        public final Float peakV;
        public final Float hfVoltage;

        @ParcelConstructor
        TuneResult(boolean lf, boolean hf, float[] lfVoltages, Float v125, Float v134, Float peakF,
                Float peakV, Float hfVoltage) {
            this.lf = lf;
            this.hf = hf;
            this.lfVoltages = lfVoltages;
            this.v125 = v125;
            this.v134 = v134;
            this.peakF = peakF;
            this.peakV = peakV;
            this.hfVoltage = hfVoltage;
        }
    }
}
