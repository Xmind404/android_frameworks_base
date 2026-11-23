/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.flashlight.dualtone

import android.os.Bundle
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.statusbar.phone.DialogDelegate
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import com.android.systemui.util.Assert
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Long-press panel of the flashlight tile on dual-tone torch devices: a dimmed, blurred backdrop
 * with two vertical sliders in the middle of the screen, white LED on the left and warm LED on
 * the right, each from off to full brightness. Back or a tap outside the sliders closes it; the
 * mix is applied live and kept by [DualToneTorch].
 */
@SysUISingleton
class DualToneTorchDialogDelegate
@Inject
constructor(
    private val sysuiDialogFactory: SystemUIDialogFactory,
    private val shadeDialogContextInteractor: ShadeDialogContextInteractor,
    private val dualToneTorch: DualToneTorch,
) {
    private var currentDialog: SystemUIDialog? = null

    fun showDialog() {
        Assert.isMainThread()
        currentDialog?.dismiss()
        val dialog =
            sysuiDialogFactory.create(
                context = shadeDialogContextInteractor.context,
                dialogDelegate =
                    object : DialogDelegate<SystemUIDialog> {
                        override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
                            super.onCreate(dialog, savedInstanceState)
                            dialog.setCanceledOnTouchOutside(true)
                            dialog.window?.apply {
                                addFlags(
                                    WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                                        WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                                )
                                setDimAmount(DIM_AMOUNT)
                                attributes = attributes.apply { blurBehindRadius = BLUR_RADIUS }
                            }
                        }

                        override fun onStop(dialog: SystemUIDialog) {
                            if (currentDialog === dialog) {
                                currentDialog = null
                            }
                        }
                    },
            ) {
                DualToneTorchContent()
            }
        currentDialog = dialog
        dialog.show()
    }

    @Composable
    private fun DualToneTorchContent() {
        var white by remember { mutableFloatStateOf(dualToneTorch.whiteLevel.toFloat()) }
        var warm by remember { mutableFloatStateOf(dualToneTorch.warmLevel.toFloat()) }
        val description = stringResource(R.string.dual_tone_torch_description)

        Row(
            modifier =
                Modifier.fillMaxWidth().padding(vertical = 24.dp).semantics {
                    contentDescription = description
                },
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TorchSlider(
                value = white,
                color = Color.White,
                label = stringResource(R.string.dual_tone_torch_white),
                onValueChange = {
                    white = it
                    dualToneTorch.setLevels(white.roundToInt(), warm.roundToInt())
                },
                onValueChangeFinished = {
                    dualToneTorch.setLevels(white.roundToInt(), warm.roundToInt())
                },
            )
            TorchSlider(
                value = warm,
                color = WARM_COLOR,
                label = stringResource(R.string.dual_tone_torch_warm),
                onValueChange = {
                    warm = it
                    dualToneTorch.setLevels(white.roundToInt(), warm.roundToInt())
                },
                onValueChangeFinished = {
                    dualToneTorch.setLevels(white.roundToInt(), warm.roundToInt())
                },
            )
        }
    }

    @Composable
    private fun TorchSlider(
        value: Float,
        color: Color,
        label: String,
        onValueChange: (Float) -> Unit,
        onValueChangeFinished: () -> Unit,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // A horizontal slider rotated by -90 degrees, so dragging up brightens.
            Box(
                modifier = Modifier.size(width = SLIDER_THICKNESS.dp, height = SLIDER_LENGTH.dp),
                contentAlignment = Alignment.Center,
            ) {
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    onValueChangeFinished = onValueChangeFinished,
                    valueRange = 0f..DualToneTorch.MAX_LEVEL.toFloat(),
                    colors =
                        SliderDefaults.colors(
                            thumbColor = color,
                            activeTrackColor = color,
                            inactiveTrackColor = color.copy(alpha = 0.3f),
                        ),
                    modifier = Modifier.requiredWidth(SLIDER_LENGTH.dp).rotate(-90f),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = label, color = Color.White)
        }
    }

    private companion object {
        const val DIM_AMOUNT = 0.6f
        const val BLUR_RADIUS = 80
        const val SLIDER_LENGTH = 280
        const val SLIDER_THICKNESS = 64
        val WARM_COLOR = Color(0xFFFFC14D)
    }
}
