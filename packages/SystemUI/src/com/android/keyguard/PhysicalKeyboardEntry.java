/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.keyguard;

import android.content.Context;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

/**
 * PIN and password entry from a built-in physical keyboard on the bouncer, such as the
 * BlackBerry KEY2 keyboard.
 *
 * <p>Digits are taken from the active key character map of the keyboard (base layer first,
 * then the Alt layer), so PIN digits can be typed without holding Alt and the mapping follows
 * the actual QWERTY/QWERTZ/AZERTY map and any selected overlay instead of a fixed key table.
 *
 * <p>A key typed on the lock screen before the bouncer is shown is kept briefly and replayed
 * into the PIN or password field once the bouncer resumes, so the first character is not lost.
 *
 * <p>Controlled by {@code Settings.Secure.keyboard_pin_input} (default enabled). All methods
 * must be called on the main thread.
 */
public final class PhysicalKeyboardEntry {
    private static final String SETTING_ENABLED = "keyboard_pin_input";
    private static final long PENDING_KEY_TIMEOUT_MS = 2000;

    private static KeyEvent sPendingKey;

    private PhysicalKeyboardEntry() {
    }

    /** Whether physical keyboard PIN/password entry is enabled for the current user. */
    public static boolean isEnabled(Context context) {
        return Settings.Secure.getIntForUser(context.getContentResolver(), SETTING_ENABLED, 1,
                UserHandle.USER_CURRENT) == 1;
    }

    /** Whether the event comes from a built-in alphabetic keyboard. */
    public static boolean isBuiltInKeyboard(KeyEvent event) {
        final InputDevice device = event.getDevice();
        return device != null && !device.isExternal() && !device.isVirtual()
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    /**
     * Returns the digit the key produces on the keyboard's numeric layer, or -1. The base layer
     * is checked first, then the Alt layer, using the device's active character map.
     */
    public static int getDigit(KeyEvent event) {
        final KeyCharacterMap kcm = event.getKeyCharacterMap();
        if (kcm == null) {
            return -1;
        }
        for (int meta : new int[] {0, KeyEvent.META_ALT_ON}) {
            final int c = kcm.get(event.getKeyCode(), meta);
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
        }
        return -1;
    }

    /**
     * Whether a key pressed on the lock screen should open the bouncer and be replayed into it:
     * a first press of a character key on the built-in keyboard while the feature is enabled.
     */
    public static boolean shouldOpenBouncer(Context context, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0
                || !isBuiltInKeyboard(event) || !isEnabled(context)) {
            return false;
        }
        return getDigit(event) >= 0 || event.isPrintingKey();
    }

    /** Keeps a lock screen key press until the bouncer resumes. */
    public static void setPendingKey(KeyEvent event) {
        sPendingKey = KeyEvent.changeTimeRepeat(event, SystemClock.uptimeMillis(), 0);
    }

    /** Returns and clears the pending key press if it is still recent, otherwise null. */
    @Nullable
    public static KeyEvent takePendingKey() {
        final KeyEvent event = sPendingKey;
        sPendingKey = null;
        if (event == null
                || SystemClock.uptimeMillis() - event.getEventTime() > PENDING_KEY_TIMEOUT_MS) {
            return null;
        }
        return event;
    }
}
