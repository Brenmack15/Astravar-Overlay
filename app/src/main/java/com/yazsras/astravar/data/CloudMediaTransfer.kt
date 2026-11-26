package com.yazsras.astravar.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal val cloudMediaTargets=setOf("actor","form","world_entity","item_definition","item_instance","invention_project","attack","blueprint_version")
internal val primaryAssociationPath = Regex("media/primary/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}/association")
internal const val cloudMediaMax=4*1024*1024
internal fun cloudDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun bounded(input: InputStream,limit:Int):ByteArray = input.use { source ->
    val out=ByteArrayOutputStream();val buf=ByteArray(8192)
    while(true){val n=source.read(buf);if(n<0)break;require(out.size()+n<=limit){"Image exceeds the permitted size."};out.write(buf,0,n)}
    out.toByteArray()
}

/** Decode through a bounded intermediate. The bytes returned here are the exact saved preview and upload. */
internal fun prepareCloudImage(context:Context,uri:Uri):ByteArray {
    require(uri.scheme=="content")
    val source=bounded(requireNotNull(context.contentResolver.openInputStream(uri)),16*1024*1024)
    val opts=BitmapFactory.Options().apply { inJustDecodeBounds=true }
    BitmapFactory.decodeByteArray(source,0,source.size,opts)
    require(opts.outWidth in 1..16384 && opts.outHeight in 1..16384 && opts.outWidth.toLong()*opts.outHeight<=48_000_000L)
    opts.inJustDecodeBounds=false;opts.inSampleSize=1
    val longestSide = maxOf(opts.outWidth, opts.outHeight)
    while (longestSide > 1024 * opts.inSampleSize) opts.inSampleSize *= 2
    val bmp=requireNotNull(BitmapFactory.decodeByteArray(source,0,source.size,opts))
    return try {
        val out=ByteArrayOutputStream();require(bmp.compress(Bitmap.CompressFormat.PNG,100,out))
        out.toByteArray().also { require(it.size in 1..cloudMediaMax) }
    } finally { bmp.recycle() }
}

internal fun transferCloudImage(session:CloudSession,target:String,kind:String,uploadId:String?,image:ByteArray?):ByteArray {
    require(cloudIdentifier.matches(target) && !target.contains("..") && kind in cloudMediaTargets)
    val uploading=uploadId!=null
    require(uploading==(image!=null))
    val c=session.credential
    val origin=OwnerSyncProtocol.origin(c.origin)
    require(c.expiresAt>System.currentTimeMillis() && cloudIdentifier.matches(c.campaignId))
    val path=if(uploadId==null)"primary/$target/thumbnail" else {
        require(cloudIdentifier.matches(uploadId) && !uploadId.contains("..") && image!!.size in 1..cloudMediaMax)
        "uploads/$uploadId?targetKind=$kind&targetId=$target"
    }
    val connection=URL("$origin/v3/continuities/${c.campaignId}/media/$path").openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects=false;connection.useCaches=false;connection.connectTimeout=10000;connection.readTimeout=20000
        connection.setRequestProperty("Authorization","Bearer ${c.bearer}");connection.setRequestProperty("X-Astravar-Credential",c.credentialId)
        session.access?.let {require(it.none { ch -> ch=='\r'||ch=='\n' });connection.setRequestProperty("OAI-Sites-Authorization","Bearer $it")}
        connection.setRequestProperty("Accept",if(uploading)"application/json" else "image/png")
        if(image!=null){
            connection.requestMethod="PUT";connection.doOutput=true
            connection.setRequestProperty("Content-Type","image/png");connection.setRequestProperty("X-Astravar-Content-SHA256",cloudDigest(image))
            connection.setFixedLengthStreamingMode(image.size);connection.outputStream.use { it.write(image) }
        }
        val status=connection.responseCode
        if (status == 429) throw OwnerSyncFailure("CLOUD_RATE_LIMITED", connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
        if(status !in 200..299)throw OwnerSyncFailure("MEDIA_HTTP_$status")
        val mime=connection.contentType?.substringBefore(';')?.trim()?.lowercase()
        require(mime==(if(uploading)"application/json" else "image/png"))
        val bytes=bounded(connection.inputStream,if(uploading)65536 else cloudMediaMax)
        if(!uploading)require(cloudDigest(bytes)==connection.getHeaderField("X-Astravar-Content-SHA256"))
        return bytes
    } finally { connection.disconnect() }
}
