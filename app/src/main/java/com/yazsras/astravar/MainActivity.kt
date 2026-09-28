package com.yazsras.astravar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.yazsras.astravar.overlay.OverlayService
import com.yazsras.astravar.ui.AstravarScreen

class MainActivity: ComponentActivity() {
    private val notificationRequest=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(this,if(granted) "Notifications enabled. Tap Start overlay." else "Notifications remain off. Enable them when you want a visible session notification.",Toast.LENGTH_LONG).show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { AstravarScreen(application as AstravarApp,::startOverlay,::grantOverlay,::stopOverlay) }
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
