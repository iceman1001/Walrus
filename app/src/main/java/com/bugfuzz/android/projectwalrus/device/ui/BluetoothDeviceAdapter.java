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

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bugfuzz.android.projectwalrus.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Two sections - already paired devices, then whatever the current scan has turned up - drawn as
 * one flat list with header rows.
 */
class BluetoothDeviceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_DEVICE = 1;

    private final List<Object> rows = new ArrayList<>();

    private final OnBluetoothDeviceClickCallback callback;

    private String rememberedAddress;

    BluetoothDeviceAdapter(OnBluetoothDeviceClickCallback callback) {
        this.callback = callback;
    }

    void setDevices(List<BluetoothDevice> paired, List<BluetoothDevice> discovered,
            String rememberedAddress) {
        this.rememberedAddress = rememberedAddress;

        rows.clear();

        if (!paired.isEmpty()) {
            rows.add(R.string.bluetooth_paired_devices);
            rows.addAll(paired);
        }

        if (!discovered.isEmpty()) {
            rows.add(R.string.bluetooth_available_devices);
            rows.addAll(discovered);
        }

        notifyDataSetChanged();
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof BluetoothDevice ? TYPE_DEVICE : TYPE_HEADER;
    }

    @Override
    @NonNull
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());

        if (viewType == TYPE_HEADER) {
            return new HeaderViewHolder(
                    inflater.inflate(R.layout.view_bluetooth_device_header, parent, false));
        }

        return new DeviceViewHolder(
                inflater.inflate(R.layout.view_bluetooth_device, parent, false));
    }

    @Override
    // The activity holds BLUETOOTH_CONNECT before it ever populates this adapter.
    @SuppressLint("MissingPermission")
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);

        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).header.setText((Integer) row);
            return;
        }

        final BluetoothDevice device = (BluetoothDevice) row;
        DeviceViewHolder deviceHolder = (DeviceViewHolder) holder;

        String name;
        try {
            name = device.getName();
        } catch (SecurityException e) {
            name = null;
        }

        deviceHolder.name.setText(name != null ? name : device.getAddress());

        String detail = device.getAddress();
        if (device.getAddress().equals(rememberedAddress)) {
            detail = detail + " • " + holder.itemView.getContext()
                    .getString(R.string.bluetooth_remembered);
        }
        deviceHolder.detail.setText(detail);

        deviceHolder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                callback.onBluetoothDeviceClick(device);
            }
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    interface OnBluetoothDeviceClickCallback {
        void onBluetoothDeviceClick(BluetoothDevice bluetoothDevice);
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView header;

        HeaderViewHolder(View itemView) {
            super(itemView);

            header = itemView.findViewById(R.id.header);
        }
    }

    static class DeviceViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView detail;

        DeviceViewHolder(View itemView) {
            super(itemView);

            name = itemView.findViewById(R.id.name);
            detail = itemView.findViewById(R.id.detail);
        }
    }
}
