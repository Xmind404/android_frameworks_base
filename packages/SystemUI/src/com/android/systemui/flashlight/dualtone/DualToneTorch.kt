/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.flashlight.dualtone

import android.content.Context
import android.os.SystemClock
import android.os.SystemProperties
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.FlashlightController
import javax.inject.Inject

/**
 * Mix of a dual-tone torch (white + warm LED), for devices that set config_dualToneTorch.
 *
 * The camera HAL only switches the torch on and off. The per-LED brightness (0-100 % of each
 * LED's maximum) is kept in persistent vendor properties, and every change of
 * [PROP_APPLY] makes the device's vendor script write the levels to the LEDs. The levels are
 * re-applied whenever the torch turns on, so both LEDs light by default and the mix survives
 * reboots.
 */
@SysUISingleton
class DualToneTorch
@Inject
constructor(
    @Application context: Context,
    private val flashlightController: FlashlightController,
) : FlashlightController.FlashlightListener {

    val isSupported: Boolean = context.resources.getBoolean(R.bool.config_dualToneTorch)

    init {
        if (isSupported) {
            flashlightController.addCallback(this)
        }
    }

    val whiteLevel: Int
        get() = SystemProperties.getInt(PROP_WHITE, DEFAULT_LEVEL).coerceIn(0, MAX_LEVEL)

    val warmLevel: Int
        get() = SystemProperties.getInt(PROP_WARM, DEFAULT_LEVEL).coerceIn(0, MAX_LEVEL)

    /** Stores the mix and applies it right away if the torch is on. */
    fun setLevels(white: Int, warm: Int) {
        SystemProperties.set(PROP_WHITE, white.coerceIn(0, MAX_LEVEL).toString())
        SystemProperties.set(PROP_WARM, warm.coerceIn(0, MAX_LEVEL).toString())
        if (flashlightController.isEnabled) {
            apply()
        }
    }

    private fun apply() {
        // Any new value triggers the vendor script; it reads the levels itself.
        SystemProperties.set(PROP_APPLY, SystemClock.elapsedRealtimeNanos().toString())
    }

    override fun onFlashlightChanged(enabled: Boolean) {
        if (enabled) {
            apply()
        }
    }

    override fun onFlashlightError() {}

    override fun onFlashlightAvailabilityChanged(available: Boolean) {}

    companion object {
        const val MAX_LEVEL = 100
        private const val DEFAULT_LEVEL = 50
        private const val PROP_WHITE = "persist.vendor.torch.white_level"
        private const val PROP_WARM = "persist.vendor.torch.warm_level"
        private const val PROP_APPLY = "vendor.torch.apply"
    }
}
