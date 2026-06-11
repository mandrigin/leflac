package app.nogarbo.leflac

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.nogarbo.leflac.ui.theme.ChassisBeige
import app.nogarbo.leflac.ui.theme.FieldTheme
import app.nogarbo.leflac.ui.viewmodel.PlaybackViewModel
import app.nogarbo.leflac.ui.components.GlyphToy

class GlyphToyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Always On Display behavior
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Hide Status Bar for immersive "Toy" feel
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        
        setContent {
            FieldTheme {
                // Access the shared Visualizer Bus (Quick solution for data sharing)
                // In a full architecture, we'd bind to the service, but PlaybackViewModel.VisualizerBus is our established bridge.
                val spectrum by PlaybackViewModel.VisualizerBus.spectrum.collectAsState()
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ChassisBeige)
                ) {
                    GlyphToy(
                        spectrum = spectrum,
                        isPlaying = true, // Always play in Toy Mode
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
