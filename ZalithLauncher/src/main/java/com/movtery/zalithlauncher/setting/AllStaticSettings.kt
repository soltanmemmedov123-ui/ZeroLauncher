package com.movtery.zalithlauncher.setting

/**
 * Holds temporary runtime-only setting values.
 *
 * These values are not persisted to the settings configuration,
 * so they will be lost when the app restarts.
 */
class AllStaticSettings {
    companion object {

        /**
         * Display cutout (notch) width.
         */
        @JvmField
        var notchSize: Int = 0

        /**
         * Resolution scale factor.
         */
        @JvmField
        var scaleFactor: Float = AllSettings.resolutionRatio.getValue() / 100f

        /**
         * Whether double-tap item swapping is disabled.
         */
        @JvmField
        var disableDoubleTap: Boolean = AllSettings.disableDoubleTap.getValue()

        /**
         * Force GUI input for ImGui/Axiom.
         */
        @JvmField
        var forceGuiInput: Boolean = AllSettings.forceGuiInput.getValue()

        /**
         * Long-press trigger delay.
         */
        @JvmField
        var timeLongPressTrigger: Int = AllSettings.timeLongPressTrigger.getValue()

        /**
         * Whether gyro controls are enabled.
         */
        @JvmField
        var enableGyro: Boolean = AllSettings.enableGyro.getValue()

        /**
         * Gyro control sensitivity.
         */
        @JvmField
        var gyroSensitivity: Int = AllSettings.gyroSensitivity.getValue()

        /**
         * Whether the gyro X axis is inverted.
         */
        @JvmField
        var gyroInvertX: Boolean = AllSettings.gyroInvertX.getValue()

        /**
         * Whether the gyro Y axis is inverted.
         */
        @JvmField
        var gyroInvertY: Boolean = AllSettings.gyroInvertY.getValue()

        /**
         * Whether to use the controller proxy.
         */
        @JvmField
        var useControllerProxy: Boolean = false
    }
}