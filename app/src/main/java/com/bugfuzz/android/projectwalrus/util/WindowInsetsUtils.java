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

import android.app.Activity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Edge-to-edge is mandatory from targetSdk 35 on, so activity content would otherwise be drawn
 * underneath the status and navigation bars.
 */
public final class WindowInsetsUtils {

    private WindowInsetsUtils() {
    }

    /**
     * Insets the activity's content view by the system bars and the display cutout, which keeps
     * every existing layout looking the way it did before edge-to-edge was enforced.
     */
    public static void insetContentBySystemBars(@NonNull Activity activity) {
        final View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }

        ViewCompat.setOnApplyWindowInsetsListener(content, new OnApplyWindowInsetsListener() {
            @Override
            @NonNull
            public WindowInsetsCompat onApplyWindowInsets(@NonNull View view,
                    @NonNull WindowInsetsCompat windowInsets) {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return WindowInsetsCompat.CONSUMED;
            }
        });
    }
}
