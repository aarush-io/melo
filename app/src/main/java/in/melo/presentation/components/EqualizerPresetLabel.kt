package `in`.melo.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import `in`.melo.R
import `in`.melo.data.equalizer.EqualizerPreset

/**
 * Localized label for an equalizer preset. Built-in presets (and the unsaved "custom" one) map to
 * string resources and are uppercased like their stored [EqualizerPreset.displayName]; user-saved
 * presets keep the name the user typed.
 */
@Composable
fun presetLabel(preset: EqualizerPreset): String {
    val res = when {
        preset.name == "custom" -> R.string.eq_preset_custom
        preset.isCustom -> null
        else -> when (preset.name) {
            "flat" -> R.string.eq_preset_flat
            "rock" -> R.string.eq_preset_rock
            "pop" -> R.string.eq_preset_pop
            "hip_hop" -> R.string.eq_preset_hip_hop
            "jazz" -> R.string.eq_preset_jazz
            "classical" -> R.string.eq_preset_classical
            "electronic" -> R.string.eq_preset_electronic
            "bass_boost" -> R.string.eq_preset_bass_boost
            "treble_boost" -> R.string.eq_preset_treble_boost
            "vocal" -> R.string.eq_preset_vocal
            else -> null
        }
    }
    return res?.let { stringResource(it).uppercase() } ?: preset.displayName
}
