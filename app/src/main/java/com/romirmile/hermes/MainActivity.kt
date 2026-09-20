package com.romirmile.hermes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.romirmile.hermes.ui.HermesApp
import com.romirmile.hermes.ui.HermesViewModel
import com.romirmile.hermes.vm.EngineController
import com.romirmile.hermes.vm.EngineStore
import com.romirmile.hermes.vm.VmService

class MainActivity : ComponentActivity() {

    private val vm: HermesViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationPermission()
        VmService.ensureChannel(this)
        setContent { HermesApp(vm) }
        // Bring the engine up in the background: booting the VM and the agent takes a while, and the
        // first reply should not have to wait for a manual start.
        if (EngineStore(this).autoStart) EngineController.start(this)
    }

    /**
     * The app notifies when a reply lands while it is in the background, so the permission is worth
     * asking for once — but a refusal costs nothing, posting just stays silent.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        vm.appVisible = true
    }

    override fun onStop() {
        vm.appVisible = false
        super.onStop()
    }
}
