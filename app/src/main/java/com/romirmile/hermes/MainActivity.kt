package com.romirmile.hermes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.romirmile.hermes.ui.HermesApp
import com.romirmile.hermes.vm.EngineController
import com.romirmile.hermes.vm.EngineStore
import com.romirmile.hermes.vm.VmService

/**
 * One activity: the engine screen brings the Linux VM up, and the WebView hosting the Hermes
 * interface takes over as soon as the dashboard answers inside the guest.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationPermission()
        VmService.ensureChannel(this)
        setContent { HermesApp() }
        // Bring the engine up in the background: booting the VM and the dashboard takes a while under
        // emulation, and the first screen should not have to wait for a manual start.
        if (EngineStore(this).autoStart) EngineController.start(this)
    }

    /**
     * The engine posts a notification while it keeps the VM alive in the background, so the
     * permission is worth asking for once — but a refusal costs nothing, posting just stays silent.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
