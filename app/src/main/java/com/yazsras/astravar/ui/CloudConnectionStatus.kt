package com.yazsras.astravar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.yazsras.astravar.data.CloudSnapshot
import java.text.DateFormat
import java.util.Date

@Composable internal fun CloudConnectionStatus(snapshot:CloudSnapshot,verified:Boolean,paused:Boolean,failure:String?,reviews:Int?,lastStatus:String?) {
    val authenticated = when {
        verified -> "Device access verified"
        failure=="CLOUD_DEVICE_GRANT_REVOKED" -> "Device grant revoked"
        failure=="CLOUD_DEVICE_GRANT_EXPIRED" -> "Device grant expired"
        failure=="CREDENTIAL_REVOKED_OR_EXPIRED" -> "Device credential revoked or expired"
        failure=="AUTHENTICATION_REQUIRED" -> "Device authorization required"
        else -> "Device access unverified"
    }
    val availability=when {
        verified -> "Cloud available"
        failure in setOf("NETWORK_TIMEOUT","NETWORK_UNAVAILABLE") -> "Cloud offline or unreachable"
        failure=="CLOUD_RATE_LIMITED" -> "Cloud request limit reached"
        failure=="TLS_FAILURE" -> "Secure connection unavailable"
        else -> "Cloud availability unknown"
    }
    Text(availability,style=MaterialTheme.typography.titleMedium)
    Text(authenticated)
    if(paused) Text("Automatic device refresh paused")
    if(snapshot.visible) {
        Text(if(verified) "Cache verified at revision ${snapshot.version.revision}" else "Cached revision ${snapshot.version.revision} · may be stale")
        if(snapshot.checkedAt>0)Text("Last checked " + DateFormat.getDateTimeInstance().format(Date(snapshot.checkedAt)), style=MaterialTheme.typography.bodySmall)
    } else Text("Cached campaign data unavailable")
    if(reviews!=null)Text("Requests ready for owner review · $reviews")
    lastStatus?.let { Text("Last submitted result · ${cloudLabel(it.lowercase())}") }
    PlayerDisclosure("Connection details") { Text("Continuity ${snapshot.binding.continuity}");Text("Authority epoch ${snapshot.version.epoch}") }
}
