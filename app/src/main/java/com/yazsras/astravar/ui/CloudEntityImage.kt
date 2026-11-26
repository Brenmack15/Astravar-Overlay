package com.yazsras.astravar.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

@Composable internal fun CloudEntityImage(client:CloudCampaignClient,snapshot:CloudSnapshot,online:Boolean,targetId:String,targetKind:String,label:String,
    modifier:Modifier=Modifier.size(88.dp)) {
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var dialog by rememberSaveable(targetId) { mutableStateOf(false) }
    var primary by remember(snapshot.binding,targetId,snapshot.version.grantRevision) { mutableStateOf<ByteArray?>(null) }
    var candidate by remember(targetId,snapshot.binding) { mutableStateOf<ByteArray?>(null) }
    var upload by remember(targetId) { mutableStateOf<String?>(null) }
    var asset by remember(targetId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember(targetId) { mutableStateOf(false) }
    LaunchedEffect(snapshot.version,snapshot.visible,online,targetId) {
        if(!snapshot.visible){primary=null;candidate=null;return@LaunchedEffect}
        try {
            primary=client.cachedImage(targetId)
            if(online)primary=client.image(snapshot,targetId,targetKind)
        }
        catch(e:CancellationException){throw e}
        catch(e:Exception){ if((e as? OwnerSyncFailure)?.code in setOf("MEDIA_HTTP_401","MEDIA_HTTP_403","MEDIA_HTTP_404"))primary=null }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){ uri->
        if(uri!=null)scope.launch {
            busy=true
            try { candidate=withContext(Dispatchers.IO){prepareCloudImage(context,uri)};upload=UUID.randomUUID().toString();asset=UUID.randomUUID().toString();error=null }
            catch(e:CancellationException){throw e}
            catch(_:Exception){error="Choose a readable image within the size limit."}
            finally {busy=false}
        }
    }
    Box(modifier.clickable(role=Role.Button,onClickLabel="Manage image for $label"){
        candidate=null;upload=null;asset=null;error=null;dialog=true
    },contentAlignment=Alignment.Center) {
        val bitmap=remember(primary){primary?.let{BitmapFactory.decodeByteArray(it,0,it.size)?.asImageBitmap()}}
        if(bitmap!=null)Image(bitmap,label,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
        else ArcaneIcon("Emblem",Modifier.size(42.dp),RebuildPalette.Gold)
    }
    if(dialog && snapshot.visible) PlayerSheet("$label image",{if(!busy){dialog=false;candidate=null}},dirty=candidate!=null,dismissEnabled=!busy){
        val chosen=candidate ?: primary
        val visible=remember(chosen){chosen?.let{BitmapFactory.decodeByteArray(it,0,it.size)?.asImageBitmap()}}
        visible?.let{Image(it,"Image preview for $label",Modifier.fillMaxWidth().heightIn(max=260.dp),contentScale=ContentScale.Fit)}
        PlayerOutlinedButton({picker.launch(arrayOf("image/*"))},enabled=online && !busy){Text(if(primary==null)"Choose image" else "Replace image")}
        if(pending)Text("Candidate submitted for owner review.")
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        PlayerButton({
            val data=candidate?:return@PlayerButton
            val id=upload?:return@PlayerButton
            val assetId=asset?:return@PlayerButton
            if(busy)return@PlayerButton
            busy=true;error=null
            scope.launch{
                try{
                    val proof=Json.parseToJsonElement(client.image(snapshot,targetId,targetKind,id,data).decodeToString()).jsonObject
                    val hash=cloudDigest(data)
                    require(proof.cloudText("status")=="CANDIDATE_READY" && proof.cloudText("uploadId")==id && proof.cloudText("targetId")==targetId &&
                        proof.cloudText("targetKind")==targetKind && proof.cloudText("sha256")==hash && proof.getValue("byteLength").jsonPrimitive.int==data.size)
                    val result=client.submit("assign_primary_image",buildJsonObject{
                        put("uploadId",id);put("assetId",assetId);put("targetId",targetId);put("targetKind",targetKind);put("sha256",hash)
                    },snapshot,"assign-$id")
                    pending=result.cloudText("status")=="PENDING_APPROVAL"
                    candidate=null
                    if(!pending)dialog=false
                }catch(e:CancellationException){throw e}
                catch(e:Exception){error=cloudError(e)}
                finally{busy=false}
            }
        },enabled=candidate!=null && online && !busy){Text("Save image")}
        PlayerTextButton({dialog=false;candidate=null},enabled=!busy){Text("Cancel")}
    }
}
