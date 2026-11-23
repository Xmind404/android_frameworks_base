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

package com.android.server.input;

import static android.hardware.input.KeyboardLayoutSelectionResult.FAILED;
import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_DEFAULT;
import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_USER;
import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD;

import android.hardware.input.KeyboardLayout;
import android.hardware.input.KeyboardLayoutSelectionResult;
import android.os.LocaleList;

import java.util.Locale;

/** Selection policy for the built-in BlackBerry STMPE physical keyboard. */
final class BlackBerryKeyboardLayoutPolicy {
    static final int VENDOR_ID = 0x0483;
    static final int PRODUCT_ID = 0x1850;
    static final String PROVIDER_PREFIX = "org.lineageos.blackberry.inputdevices/"
            + "org.lineageos.blackberry.inputdevices.InputDeviceReceiver/keyboard_";
    private static final String BLACKBERRY_IME = "com.blackberry.keyboard";

    private BlackBerryKeyboardLayoutPolicy() {}

    static String getFamily(String name, int vendorId, int productId,
            boolean external, boolean virtual) {
        if (external || virtual || vendorId != VENDOR_ID || productId != PRODUCT_ID) {
            return null;
        }
        switch (name) {
            case "stmpe_keypad":
                return "qwerty";
            case "stmpe_azerty_keypad":
                return "azerty";
            case "stmpe_qwertz_keypad":
                return "qwertz";
            default:
                return null;
        }
    }

    static boolean isProviderLayout(KeyboardLayout layout) {
        return layout.getDescriptor().startsWith(PROVIDER_PREFIX);
    }

    static boolean isCompatibleLayout(KeyboardLayout layout, String family) {
        return layout.getDescriptor().startsWith(PROVIDER_PREFIX + family + "_")
                && family.equals(layout.getLayoutType())
                && layout.getVendorId() == VENDOR_ID && layout.getProductId() == PRODUCT_ID;
    }

    static KeyboardLayoutSelectionResult select(String family, KeyboardLayout[] layouts,
            String userDescriptor, String imePackage, int subtypeHash, String languageTag,
            String keyboardLayoutSet) {
        // Preserve a deliberate choice only if it is still available and belongs
        // to this hardware family. Generic layouts cannot preserve STMPE Alt/Sym.
        userDescriptor = redirectRetiredLayout(family, userDescriptor);
        if (find(layouts, family, userDescriptor) != null) {
            return new KeyboardLayoutSelectionResult(userDescriptor,
                    LAYOUT_SELECTION_CRITERIA_USER);
        }

        final Locale requested = Locale.forLanguageTag(
                languageTag == null ? "" : languageTag.replace('_', '-'));
        final String language = canonicalLanguage(requested.getLanguage());
        final String transliteration = BLACKBERRY_IME.equals(imePackage)
                ? getTransliterationSuffix(subtypeHash, language) : null;
        if (transliteration != null) {
            String descriptor = find(layouts, family,
                    PROVIDER_PREFIX + family + "_" + transliteration);
            return descriptor != null ? new KeyboardLayoutSelectionResult(descriptor,
                    LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD) : nativeLayout(layouts, family);
        }

        // Chinese methods share a language tag. Select the stock map by the
        // IME's declared input mode, never by a country/language tie-break.
        if (language.equals("zh")) {
            final String suffix = BLACKBERRY_IME.equals(imePackage)
                    ? getChineseSuffix(keyboardLayoutSet, requested.getVariant(), subtypeHash)
                    : null;
            final String descriptor = suffix == null ? null : find(layouts, family,
                    PROVIDER_PREFIX + family + "_" + suffix);
            return descriptor == null ? nativeLayout(layouts, family)
                    : new KeyboardLayoutSelectionResult(descriptor,
                            LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD);
        }

        // Keep existing English/default behavior on all hardware families.
        // GB and India have explicit currency overlays in the supplied resources.
        if (language.equals("en") && !requested.getCountry().equals("GB")
                && !requested.getCountry().equals("IN")) {
            return nativeLayout(layouts, family);
        }

        String country = requested.getCountry();
        if (language.equals("es") && isLatinAmericanCountry(country)) {
            country = "419";
        }
        String best = null;
        int bestScore = -1;
        for (KeyboardLayout layout : layouts) {
            if (!isCompatibleLayout(layout, family)
                    || layout.getDescriptor().endsWith("_translit")
                    || layout.getDescriptor().endsWith("_native")
                    // Designated adapted defaults are considered only after BB presets.
                    || layout.getDescriptor().startsWith(
                            PROVIDER_PREFIX + family + "_aosp_")
                    // These stock maps participate in BlackBerry IME conversion.
                    // Other IMEs may require a different physical character map.
                    || (isImeDependentLayout(layout)
                            && !BLACKBERRY_IME.equals(imePackage))) {
                continue;
            }
            final LocaleList locales = layout.getLocales();
            for (int i = 0; i < locales.size(); i++) {
                final Locale candidate = locales.get(i);
                if (!language.equals(canonicalLanguage(candidate.getLanguage()))
                        || language.isEmpty()) {
                    continue;
                }
                // Do not guess a different country's currency overlay. A
                // country-neutral map is allowed; otherwise preserve native.
                if (!candidate.getCountry().isEmpty()
                        && !candidate.getCountry().equals(country)) {
                    continue;
                }
                if (!candidate.getScript().isEmpty() && !requested.getScript().isEmpty()
                        && !candidate.getScript().equals(requested.getScript())) {
                    continue;
                }
                if (!candidate.getVariant().isEmpty()
                        && !candidate.getVariant().equals(requested.getVariant())) {
                    continue;
                }
                final int score = (candidate.getCountry().isEmpty() ? 2 : 4)
                        + (candidate.getScript().isEmpty() ? 0 : 1)
                        + (candidate.getVariant().isEmpty() ? 0 : 1);
                final String descriptor = layout.getDescriptor();
                if (score > bestScore || (score == bestScore
                        && (best == null || descriptor.compareTo(best) < 0))) {
                    best = descriptor;
                    bestScore = score;
                }
            }
        }
        if (best != null) {
            return new KeyboardLayoutSelectionResult(best,
                    LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD);
        }
        final String adapted = primaryAdaptedLayout(family, layouts, requested, language);
        return adapted != null ? new KeyboardLayoutSelectionResult(adapted,
                LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD) : nativeLayout(layouts, family);
    }

    // Original BB presets win. These are designated language defaults, not
    // arbitrary AOSP alternatives or named software arrangements.
    private static String primaryAdaptedLayout(String family, KeyboardLayout[] layouts,
            Locale requested, String language) {
        final String suffix;
        switch (language) {
            case "bg": suffix = "bulgarian"; break;
            case "cs": suffix = "czech"; break;
            case "da": suffix = "danish"; break;
            case "nb": suffix = "norwegian"; break;
            case "sv": suffix = "swedish"; break;
            case "hr": suffix = "croatian"; break;
            case "et": suffix = "estonian"; break;
            case "hu": suffix = "hungarian"; break;
            case "is": suffix = "icelandic"; break;
            case "lv": suffix = "latvian"; break;
            case "fa": suffix = "persian"; break;
            case "az": suffix = "azerbaijani"; break;
            case "pl": suffix = "polish"; break;
            case "mn": suffix = "mongolian"; break;
            case "ka": suffix = "georgian"; break;
            case "th": suffix = "thai_kedmanee"; break;
            case "pt":
                if (!"BR".equals(requested.getCountry())) return null;
                suffix = "brazilian";
                break;
            case "sr":
                suffix = "Latn".equals(requested.getScript())
                        ? "serbian_latin" : "serbian_cyrillic";
                break;
            case "cnr":
                suffix = "Cyrl".equals(requested.getScript())
                        ? "montenegrin_cyrillic" : "montenegrin_latin";
                break;
            default: return null;
        }
        final String descriptor = PROVIDER_PREFIX + family + "_aosp_" + suffix;
        for (KeyboardLayout layout : layouts) {
            if (!descriptor.equals(layout.getDescriptor()) || !isCompatibleLayout(layout, family)) {
                continue;
            }
            final LocaleList locales = layout.getLocales();
            for (int i = 0; i < locales.size(); i++) {
                final Locale candidate = locales.get(i);
                if (!language.equals(canonicalLanguage(candidate.getLanguage()))) continue;
                if (!requested.getCountry().isEmpty() && !candidate.getCountry().isEmpty()
                        && !requested.getCountry().equals(candidate.getCountry())) continue;
                if (!requested.getScript().isEmpty() && !candidate.getScript().isEmpty()
                        && !requested.getScript().equals(candidate.getScript())) continue;
                if (!candidate.getVariant().isEmpty()
                        && !candidate.getVariant().equals(requested.getVariant())) continue;
                return descriptor;
            }
        }
        return null;
    }

    // Compatibility redirects for previously saved v7 choices. They remain
    // deliberate user selections; unrelated devices never enter this policy.
    private static String redirectRetiredLayout(String family, String descriptor) {
        final String prefix = PROVIDER_PREFIX + family + "_aosp_";
        if (descriptor == null || !descriptor.startsWith(prefix)) return descriptor;
        final String suffix;
        switch (descriptor.substring(prefix.length())) {
            case "english_us":
                suffix = family.equals("qwerty") ? "qwerty" : "en";
                break;
            case "english_uk":
                suffix = "en_GB";
                break;
            case "finnish":
                suffix = "fi";
                break;
            case "french":
                suffix = "fr";
                break;
            case "french_ca":
                suffix = "fr_CA";
                break;
            case "german":
                suffix = "de";
                break;
            case "italian":
                suffix = "it";
                break;
            case "lithuanian":
                suffix = "lt";
                break;
            case "portuguese":
                suffix = "pt_PT";
                break;
            case "slovak":
                suffix = "sk";
                break;
            case "slovenian":
                suffix = "sl";
                break;
            case "spanish":
                suffix = "es";
                break;
            case "spanish_latin":
                suffix = "es_419";
                break;
            case "turkish":
                suffix = "tr";
                break;
            case "swiss_french":
                suffix = "fr";
                break;
            case "swiss_german":
                suffix = "de";
                break;
            case "russian_mac":
                suffix = "aosp_russian";
                break;
            case "english_us_intl":
                suffix = family.equals("qwerty") ? "qwerty" : "en";
                break;
            case "czech_qwerty":
                suffix = "aosp_czech";
                break;
            default: return descriptor;
        }
        return PROVIDER_PREFIX + family + "_" + suffix;
    }

    private static KeyboardLayoutSelectionResult nativeLayout(KeyboardLayout[] layouts,
            String family) {
        String descriptor = find(layouts, family, PROVIDER_PREFIX + family + "_native");
        // No provider/fallback resource: return no overlay, preserving base KCM.
        return descriptor == null ? FAILED : new KeyboardLayoutSelectionResult(descriptor,
                LAYOUT_SELECTION_CRITERIA_DEFAULT);
    }

    private static String find(KeyboardLayout[] layouts, String family, String descriptor) {
        if (descriptor == null) {
            return null;
        }
        for (KeyboardLayout layout : layouts) {
            if (descriptor.equals(layout.getDescriptor()) && isCompatibleLayout(layout, family)) {
                return descriptor;
            }
        }
        return null;
    }

    private static boolean isImeDependentLayout(KeyboardLayout layout) {
        final String descriptor = layout.getDescriptor();
        return descriptor.endsWith("_ko") || descriptor.endsWith("_ja")
                || descriptor.endsWith("_vi") || descriptor.endsWith("_zh_pinyin")
                || descriptor.endsWith("_zh_cangjie");
    }

    private static String getChineseSuffix(String layoutSet, String variant, int hash) {
        String mode = layoutSet == null ? "" : layoutSet;
        if (mode.isEmpty()) {
            mode = variant == null ? "" : variant;
        }
        // An explicit unsupported mode must not be overridden by a legacy hash.
        if (!mode.isEmpty()) {
            switch (mode.toLowerCase(Locale.ROOT)) {
                case "pinyin": return "zh_pinyin";
                case "cangjie": return "zh_cangjie";
                default: return null;
            }
        }
        // Fallback for older metadata that omits both input mode and variant.
        // Package and Chinese-language checks are enforced by the caller.
        switch (hash) {
            case 1599191706: case 1631168201: return "zh_pinyin";
            case -245667285: return "zh_cangjie";
            default: return null;
        }
    }

    private static String canonicalLanguage(String language) {
        switch (language) {
            case "iw": return "he";
            case "in": return "id";
            case "ji": return "yi";
            default: return language;
        }
    }

    private static String getTransliterationSuffix(int hash, String language) {
        switch (hash) {
            case -182254513: return language.equals("be") ? "be_translit" : null;
            case 93878266: return language.equals("ky") ? "ky_translit" : null;
            case 290588239: return language.equals("ru") ? "ru_translit" : null;
            case 367240482: return language.equals("uk") ? "uk_translit" : null;
            default: return null;
        }
    }

    private static boolean isLatinAmericanCountry(String country) {
        switch (country) {
            case "AR": case "BO": case "BR": case "BZ": case "CL": case "CO":
            case "CR": case "CU": case "DO": case "EC": case "GT": case "HN":
            case "HT": case "MX": case "NI": case "PA": case "PE": case "PR":
            case "PY": case "SV": case "UY": case "VE":
                return true;
            default:
                return false;
        }
    }
}
