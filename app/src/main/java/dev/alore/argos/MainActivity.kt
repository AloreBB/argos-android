package dev.alore.argos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.alore.argos.ui.ArgosNavHost
import dev.alore.argos.ui.theme.ArgosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ArgosTheme {
                ArgosNavHost()
            }
        }
    }
}
