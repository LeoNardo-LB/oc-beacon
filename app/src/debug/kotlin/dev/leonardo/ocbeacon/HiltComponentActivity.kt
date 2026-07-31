package dev.leonardo.ocbeacon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint

/**
 * Empty Hilt-enabled Activity for Compose UI testing.
 *
 * Required because `hiltViewModel()` needs the host Activity to be a
 * Hilt component holder (`@AndroidEntryPoint`). Plain `ComponentActivity`
 * is not, causing:
 *   "Given component holder class ComponentActivity does not implement
 *    GeneratedComponentManager"
 *
 * Usage in tests:
 *   createAndroidComposeRule<HiltComponentActivity>()
 */
@AndroidEntryPoint
class HiltComponentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
