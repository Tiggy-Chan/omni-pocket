package com.tiggy.qwenimage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.engine.LocalInferenceCoordinator
import com.tiggy.qwenimage.ui.LocalOmniPalette
import com.tiggy.qwenimage.ui.OmniColors
import com.tiggy.qwenimage.ui.OmniTheme
import com.tiggy.qwenimage.ui.QwenApp

class MainActivity : ComponentActivity() {
    private val inferenceCoordinator = LocalInferenceCoordinator()
    private val viewModel: QwenViewModel by viewModels {
        appFactory { QwenViewModel(application, inferenceCoordinator) }
    }
    private val llmViewModel: LlmViewModel by viewModels {
        appFactory { LlmViewModel(application, inferenceCoordinator) }
    }
    private val decisionViewModel: DecisionViewModel by viewModels {
        appFactory { DecisionViewModel(application, inferenceCoordinator) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appState by viewModel.state.collectAsStateWithLifecycle()
            OmniTheme(appState.themeMode) {
                val dark = LocalOmniPalette.current.dark
                SideEffect {
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                Surface(modifier = Modifier.fillMaxSize(), color = OmniColors.Background) {
                    QwenApp(viewModel, llmViewModel, decisionViewModel)
                }
            }
        }
    }
}

private inline fun <reified T : ViewModel> appFactory(crossinline create: () -> T) =
    object : ViewModelProvider.Factory {
        override fun <U : ViewModel> create(modelClass: Class<U>): U {
            require(modelClass.isAssignableFrom(T::class.java)) { "未知 ViewModel：${modelClass.name}" }
            @Suppress("UNCHECKED_CAST")
            return create() as U
        }
    }
