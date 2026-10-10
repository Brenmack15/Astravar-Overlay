package com.yazsras.astravar

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Review artifacts from actual Android drawing; publish a complete PNG atomically. */
internal fun savePresentationCapture(bitmap: Bitmap,name: String) {
    val directory=System.getProperty("astravar.screenshots") ?: return
    val bytes=ByteArrayOutputStream().use {output->
        check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output)) {"PNG compression failed for $name"}
        output.toByteArray()
    }
    val iend=byteArrayOf(0,0,0,0,73,69,78,68,-82,66,96,-126)
    check(bytes.size>iend.size && bytes.copyOfRange(bytes.size-iend.size,bytes.size).contentEquals(iend)) {"Incomplete PNG capture for $name"}
    val folder=Path.of(directory);Files.createDirectories(folder)
    val temporary=Files.createTempFile(folder,"$name-",".tmp")
    try {
        Files.write(temporary,bytes)
        Files.move(temporary,folder.resolve("$name.png"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
    } finally {Files.deleteIfExists(temporary)}
}
