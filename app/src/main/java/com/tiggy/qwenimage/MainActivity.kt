package com.tiggy.qwenimage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowInsetsControllerCompat
import com.tiggy.qwenimage.ui.OmniColors
import com.tiggy.qwenimage.ui.OmniTheme
import com.tiggy.qwenimage.ui.QwenApp

class MainActivity : ComponentActivity() {
    private val viewModel: QwenViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            OmniTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = OmniColors.Background) {
                    QwenApp(viewModel)
                }
            }
        }
    }
}
