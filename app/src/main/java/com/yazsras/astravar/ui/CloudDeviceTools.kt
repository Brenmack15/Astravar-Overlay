package com.yazsras.astravar.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yazsras.astravar.AstravarApp
import com.yazsras.astravar.data.OverlayPrefs
import com.yazsras.astravar.data.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun DeviceAppearancePreferences(store: Preferences, busy: Boolean, update: (String,suspend ()->Unit)->Unit) {
    val prefs by store.flow.collectAsState(initial=OverlayPrefs())
    val context=LocalContext.current
    val choose=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) update("Artwork selected") {
            context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            store.image(uri.toString())
        }
    }
    PlayerHeading("Feedback")
    Toggle("Haptic feedback",prefs.haptics,!busy){ value->update("Feedback updated"){store.haptics(value)} }
    Text("Screen transitions follow the system animation scale. Text follows your Android font size.",color=RebuildPalette.Muted,style=MaterialTheme.typography.bodySmall)
    HorizontalDivider(color=RebuildPalette.Outline.copy(alpha=.4f))
    PlayerHeading("Artwork")
    Text(if(prefs.image.isBlank()) "Included Astravar artwork is active." else "A personal image is selected.",color=RebuildPalette.Muted)
    PlayerButton({choose.launch(arrayOf("image/*"))},enabled=!busy){Text("Choose personal artwork")}
    PlayerOutlinedButton({update("Original artwork restored"){store.image("")}},enabled=!busy){Text("Use included Astravar artwork")}
    PlayerDisclosure("Advanced dice controls") {
        Toggle("Advanced: manual roll override",prefs.manualOverrides,!busy){ value->update("Manual override updated"){store.manualOverrides(value)} }
        Text("This device setting enables manual results in the archived weapon’s roll options. Connected character rolls use the authorized dice source for each action.",color=RebuildPalette.Muted)
    }
}

@Composable internal fun CloudDeviceTools(destination: String, dismiss: ()->Unit) {
    val app=LocalContext.current.applicationContext as AstravarApp
    val scope=rememberCoroutineScope()
    var busy by remember{mutableStateOf(false)}
    var message by remember(destination){mutableStateOf("")}
    Dialog(onDismissRequest=dismiss, properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=RebuildPalette.Canvas) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    PlayerTextButton(dismiss,modifier=Modifier.semantics { contentDescription="Back to Owner connection" }) { Text("Back") }
                    Text(destination,style=MaterialTheme.typography.titleLarge)
                }
                HorizontalDivider()
                Box(Modifier.fillMaxSize()) {
                    when(destination) {
                        "ChatGPT Prompts" -> ChatGptPromptsPage(app.campaignLedger.prompts)
                        "Help & diagnostics" -> HelpPage(cloudAuthoritative=true)
                        "Appearance & feedback" -> Page {
                            DeviceAppearancePreferences(app.preferences,busy) { success,action->
                                if(!busy){
                                    busy=true
                                    scope.launch {
                                        try{action();message=success}
                                        catch(e:CancellationException){throw e}
                                        catch(e:Exception){message=e.localizedMessage ?: "Device preference could not be saved."}
                                        finally{busy=false}
                                    }
                                }
                            }
                            if(message.isNotBlank())Text(message,color=RebuildPalette.Ice)
                        }
                    }
                }
            }
        }
    }
}
