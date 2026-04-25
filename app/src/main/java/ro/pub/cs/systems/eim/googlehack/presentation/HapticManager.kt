package ro.pub.cs.systems.eim.googlehack.presentation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

enum class HapticPattern {
    ANCHOR,
    PRE_FOCUS_BREATH,
    FOCUS_EXIT,
    HYPERFOCUS_SOFT,
    MEDICATION
}

object HapticManager {

    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun vibrate(context: Context, pattern: HapticPattern) {
        val timings = when (pattern) {
            HapticPattern.ANCHOR ->
                longArrayOf(0, 250, 120, 250)

            HapticPattern.PRE_FOCUS_BREATH ->
                longArrayOf(0, 500)

            HapticPattern.FOCUS_EXIT ->
                longArrayOf(0, 150, 100, 150, 100, 300)

            HapticPattern.HYPERFOCUS_SOFT ->
                longArrayOf(0, 200, 200, 200)

            HapticPattern.MEDICATION ->
                longArrayOf(0, 400, 200, 400, 200, 400)
        }

        getVibrator(context).vibrate(
            VibrationEffect.createWaveform(timings, -1)
        )
    }

    fun vibrateDuration(context: Context, durationMs: Long) {
        getVibrator(context).vibrate(
            VibrationEffect.createOneShot(
                durationMs,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
        )
    }
}