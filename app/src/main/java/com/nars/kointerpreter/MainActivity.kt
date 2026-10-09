package com.nars.kointerpreter

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nars.kointerpreter.ui.InterpreterTheme
import com.nars.kointerpreter.ui.MainScreen
import com.nars.kointerpreter.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: InterpreterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark navy, so the status and navigation bar icons stay light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            InterpreterTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var showSettings by rememberSaveable { mutableStateOf(false) }

                    // Settings slides in from the right; going back slides it out again.
                    AnimatedContent(
                        targetState = showSettings,
                        transitionSpec = {
                            val dir = if (targetState) 1 else -1
                            (slideInHorizontally(tween(300)) { dir * it / 4 } + fadeIn(tween(300)))
                                .togetherWith(slideOutHorizontally(tween(300)) { -dir * it / 4 } + fadeOut(tween(200)))
                        },
                        label = "screens",
                    ) { settingsOpen ->
                        if (settingsOpen) {
                            val settings by vm.settings.collectAsStateWithLifecycle()
                            BackHandler { showSettings = false }
                            SettingsScreen(
                                settings = settings,
                                onChange = { t -> vm.settingsRepo.update(t) },
                                onBack = { showSettings = false },
                            )
                        } else {
                            MainScreen(vm = vm, onOpenSettings = {
                                vm.stopAll()
                                showSettings = true
                            })
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Don't keep the microphone open (or a Bluetooth mic linked) in the background.
        vm.onBackground()
    }
}
