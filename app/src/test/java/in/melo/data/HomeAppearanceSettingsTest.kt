package `in`.melo.data

import `in`.melo.data.preferences.AppSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeAppearanceSettingsTest {
    @Test fun existingSettingsKeepTheirDialChoicesAndReceiveAppearanceDefaults() {
        val legacy = JSONObject("""{"homeDialLabels":false,"homeDialRows":3,"homeArtistDialEnabled":true}""")
        val settings = AppSettings.fromJson(legacy)
        assertFalse(settings.homeDialLabels)
        assertEquals(3, settings.homeDialRows)
        assertTrue(settings.homeArtistDialEnabled)
        assertTrue(settings.homeGradients)
        assertTrue(settings.surfaceGradients)
        assertTrue(settings.homePressMotion)
        assertTrue(settings.homeLoadingAnimations)
        assertTrue(settings.playerArtworkGradient)
    }

    @Test fun disabledEffectsSurviveSavingAndReloading() {
        val settings = AppSettings(homeGradients = false, surfaceGradients = false, homePressMotion = false, homeLoadingAnimations = false, playerArtworkGradient = false)
        val restored = AppSettings.fromJson(JSONObject(settings.toJson().toString()))
        assertFalse(restored.homeGradients)
        assertFalse(restored.surfaceGradients)
        assertFalse(restored.homePressMotion)
        assertFalse(restored.homeLoadingAnimations)
        assertFalse(restored.playerArtworkGradient)
        assertEquals(settings.homeDialRows, restored.homeDialRows)
        assertEquals(settings.homeDialTileStyle, restored.homeDialTileStyle)
    }

    @Test fun savedRecommendationCountryIsDroppedAndTheRestKept() {
        val settings = AppSettings.fromJson(JSONObject("""{"youtubeContentCountry":"US","language":"fr"}"""))
        assertFalse(settings.toJson().has("youtubeContentCountry"))
        assertEquals("fr", settings.language)
    }
}
