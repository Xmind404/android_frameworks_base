/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.inputmethodservice;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Compatibility for the legacy physical-key Alt overrides in BlackBerry Keyboard.
 *
 * This is deliberately an APK-version-specific workaround. It changes no APK bytes,
 * key events, system properties or key character maps. The original IME's own
 * Unicode/number/mode handling remains responsible for interpreting the event.
 *
 * @hide
 */
final class BlackBerryKeyboardCompat {
    private static final String TAG = "BlackBerryKeyboardCompat";
    private static final String PACKAGE = "com.blackberry.keyboard";
    private static final String VERSION = "4.1904.3.16701";
    private static final long VERSION_CODE = 1380;
    private static final int ALT_LEFT = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
    private static final Scope NO_CHANGE = new Scope(null, null, null);

    private boolean mChecked;
    private Field mFamilyField;
    private boolean mReportedActive;
    private boolean mReportedFailure;

    /** Called on the same service thread as the immediately following event dispatch. */
    Scope begin(Context service, KeyEvent event) {
        // Include ordinary letter events: sticky Alt belongs to the IME and need
        // not be present in event.getMetaState(). Never synthesize a modifier.
        if (event.getAction() != KeyEvent.ACTION_DOWN
                && event.getAction() != KeyEvent.ACTION_UP) {
            return NO_CHANGE;
        }
        final int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_A && keyCode != KeyEvent.KEYCODE_Q
                && keyCode != KeyEvent.KEYCODE_W && keyCode != KeyEvent.KEYCODE_Y
                && keyCode != KeyEvent.KEYCODE_Z) {
            return NO_CHANGE;
        }
        final InputDevice device = event.getDevice();
        if (device == null || device.isExternal() || device.isVirtual()
                || device.getVendorId() != 0x0483 || device.getProductId() != 0x1850
                || !isLetterScan(event.getScanCode())) {
            return NO_CHANGE;
        }
        final String family;
        if ("stmpe_qwertz_keypad".equals(device.getName())) {
            family = "qwertz";
        } else if ("stmpe_azerty_keypad".equals(device.getName())) {
            family = "azerty";
        } else {
            return NO_CHANGE;
        }
        final int hardcoded = legacyAlt(family, keyCode);
        if (hardcoded == 0) {
            return NO_CHANGE;
        }
        try {
            // Only intervene when the selected map actually disagrees with the
            // IME override. The live device map already includes its overlay.
            final int mapped = event.getUnicodeChar(ALT_LEFT);
            if (mapped == 0 || mapped == hardcoded) {
                return NO_CHANGE;
            }
            if (!mChecked) {
                resolve(service);
            }
            if (mFamilyField == null) {
                return NO_CHANGE;
            }
            final Object saved = mFamilyField.get(null);
            if (!family.equals(saved)) {
                return NO_CHANGE;
            }
            // The inspected decoder uses equals(), not identity. A distinct
            // object lets close() detect a cache update made by the IME itself.
            final String temporary = new String("qwerty");
            final Scope scope = new Scope(mFamilyField, saved, temporary);
            mFamilyField.set(null, temporary);
            if (!mReportedActive) {
                mReportedActive = true;
                Log.i(TAG, "Using selected STMPE character map for legacy IME Alt overrides");
            }
            return scope;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // An unsupported or inaccessible private implementation keeps its
            // original behavior; it must never prevent delivery of the event.
            mFamilyField = null;
            if (!mReportedFailure) {
                mReportedFailure = true;
                Log.w(TAG, "Legacy IME compatibility unavailable; retaining original behavior", e);
            }
            return NO_CHANGE;
        }
    }

    private void resolve(Context service) throws ReflectiveOperationException {
        mChecked = true;
        if (!PACKAGE.equals(service.getPackageName())) {
            return;
        }
        final PackageInfo info;
        try {
            info = service.getPackageManager().getPackageInfo(PACKAGE, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return;
        }
        if (!VERSION.equals(info.versionName) || info.getLongVersionCode() != VERSION_CODE) {
            return;
        }
        final Class<?> owner = Class.forName(
                "com.blackberry.inputmethod.core.utils.ad", false, service.getClassLoader());
        final Field field = owner.getDeclaredField("c");
        final int modifiers = field.getModifiers();
        if (field.getType() != String.class || !Modifier.isStatic(modifiers)
                || Modifier.isFinal(modifiers)) {
            return;
        }
        field.setAccessible(true);
        mFamilyField = field;
    }

    private static int legacyAlt(String family, int keyCode) {
        if ("qwertz".equals(family)) {
            if (keyCode == KeyEvent.KEYCODE_Z) return ')';
            if (keyCode == KeyEvent.KEYCODE_Y) return '7';
        } else if ("azerty".equals(family)) {
            if (keyCode == KeyEvent.KEYCODE_A) return '#';
            if (keyCode == KeyEvent.KEYCODE_Q) return '*';
            if (keyCode == KeyEvent.KEYCODE_W) return '7';
            if (keyCode == KeyEvent.KEYCODE_Z) return '1';
        }
        return 0;
    }

    private static boolean isLetterScan(int scan) {
        return (scan >= 16 && scan <= 25) || (scan >= 30 && scan <= 38)
                || (scan >= 44 && scan <= 50);
    }

    static final class Scope implements AutoCloseable {
        private final Field mField;
        private final Object mSaved;
        private final String mTemporary;

        private Scope(Field field, Object saved, String temporary) {
            mField = field;
            mSaved = saved;
            mTemporary = temporary;
        }

        @Override
        public void close() {
            if (mField == null) return;
            try {
                // Do not undo a re-entrant configuration update by the IME.
                if (mField.get(null) == mTemporary) {
                    mField.set(null, mSaved);
                }
            } catch (IllegalAccessException | RuntimeException e) {
                Log.e(TAG, "Could not restore legacy IME family cache", e);
            }
        }
    }
}
