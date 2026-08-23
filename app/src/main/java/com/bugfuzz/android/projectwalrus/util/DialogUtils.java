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

package com.bugfuzz.android.projectwalrus.util;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;

/**
 * Helpers replacing the material-dialogs builder methods this app used to rely
 * on, so that {@code MaterialAlertDialogBuilder} behaves the same way.
 */
public final class DialogUtils {

    private DialogUtils() {
    }

    /**
     * Equivalent of material-dialogs' {@code customView(view, wrapInScrollView)}: hand the result
     * to {@code MaterialAlertDialogBuilder.setView}, but keep {@code view} itself as the handle
     * that {@code MaterialDialog.getCustomView()} used to return.
     */
    @NonNull
    public static View asCustomView(@NonNull Context context, @NonNull View view,
            boolean wrapInScrollView) {
        if (!wrapInScrollView) {
            return view;
        }

        NestedScrollView scrollView = new NestedScrollView(context);
        scrollView.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scrollView;
    }

    /**
     * Equivalent of material-dialogs' {@code customView(layoutId, wrapInScrollView)}.
     */
    @NonNull
    public static View inflateCustomView(@NonNull Context context, @LayoutRes int layoutId) {
        return LayoutInflater.from(context).inflate(layoutId, null, false);
    }
}
