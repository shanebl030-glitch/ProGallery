package com.shaneblackburn.progallery

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import com.shaneblackburn.progallery.ui.GalleryViewModel
import com.shaneblackburn.progallery.ui.ProGalleryRoot
import com.shaneblackburn.progallery.ui.theme.ProGalleryTheme

/**
 * Single-activity host. Extends FragmentActivity (a ComponentActivity) because the
 * AndroidX BiometricPrompt used for hidden albums requires it.
 */
class MainActivity : FragmentActivity() {

    private val vm: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings.collectAsState()
            ProGalleryTheme(mode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                ProGalleryRoot(vm)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Hidden albums re-lock whenever the app leaves the foreground (not on rotation).
        if (!isChangingConfigurations) vm.lockHidden()
    }
}
