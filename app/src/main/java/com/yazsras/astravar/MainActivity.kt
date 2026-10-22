package com.yazsras.astravar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.yazsras.astravar.data.CampaignConnectionClient
import com.yazsras.astravar.data.CampaignSyncNotifications
import com.yazsras.astravar.data.CampaignSyncPreferences
import com.yazsras.astravar.data.LiveHpConnectionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.yazsras.astravar.overlay.OverlayService
import com.yazsras.astravar.ui.AstravarScreen

class MainActivity: ComponentActivity() {
    private var ownerConnectionRequest by mutableLongStateOf(0L)
    private val notificationRequest=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(this,if(granted) "Notifications enabled. Tap Start overlay." else "Notifications remain off. Enable them when you want a visible session notification.",Toast.LENGTH_LONG).show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == CampaignSyncNotifications.OPEN_SETTINGS) ownerConnectionRequest++
        // Astravar stays dark even when Android uses its light system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent { AstravarScreen(application as AstravarApp,::startOverlay,::grantOverlay,::stopOverlay, ownerConnectionRequest) }
        // Deliver delegated updates throughout active app use, not only in Config.
        lifecycleScope.launch(Dispatchers.IO) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val campaign = CampaignConnectionClient(applicationContext)
                try {
                    while (isActive) {
                        try { if (!CampaignSyncPreferences(applicationContext).paused) campaign.foreground() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* Persistent journal/status remains available for review. */ }
                        delay(5_000)
                    }
                } finally { campaign.close() }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == CampaignSyncNotifications.OPEN_SETTINGS) ownerConnectionRequest++
    }
    override fun onStart() {
        super.onStart()
        // Pairing authorizes only the selected existing profile's HP. No unpaired record is uploaded.
        lifecycleScope.launch(Dispatchers.IO) {
            var hp: LiveHpConnectionClient? = null
            try { if (!CampaignSyncPreferences(applicationContext).paused) { val opened = LiveHpConnectionClient(applicationContext); hp = opened; opened.foreground() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Durable status/outbox is shown in Owner connection; foreground startup stays usable. */ }
            finally { hp?.close() }
        }
    }
    private fun grantOverlay() {
        stopOverlay()
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
    }
    private fun startOverlay() {
        if(!Settings.canDrawOverlays(this)) { grantOverlay();Toast.makeText(this,"Allow Astravar to appear on top, then return and tap Floating controls.",Toast.LENGTH_LONG).show(); return }
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS); return
        }
        try { ContextCompat.startForegroundService(this,Intent(this,OverlayService::class.java)) }
        catch(e: RuntimeException) { Toast.makeText(this,"Session could not start: ${e.message}",Toast.LENGTH_LONG).show() }
    }
    private fun stopOverlay() { stopService(Intent(this,OverlayService::class.java)) }
}
