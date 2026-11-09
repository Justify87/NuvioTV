package com.nuvio.tv.core.vpn

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_WIREGUARD_PROFILE_BYTES = 65_536

/**
 * Imports a user-selected content URI once and keeps no persistent permission or
 * plaintext copy. Both the Android picker and the external share receiver use this
 * path, so all imported profiles pass WireGuardManager's parser, route policy and
 * Android Keystore encryption.
 */
suspend fun importWireGuardProfileFromUri(
    context: Context,
    manager: WireGuardManager,
    uri: Uri
): WireGuardProfile = withContext(Dispatchers.IO) {
    require(uri.scheme == ContentResolver.SCHEME_CONTENT) {
        "Die Datei muss über einen Datei-Explorer freigegeben oder ausgewählt werden."
    }
    val filename = runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()
    val displayName = safeWireGuardFilename(filename)
    val profileBytes = context.contentResolver.openInputStream(uri)?.use(::readLimitedWireGuardProfile)
        ?: throw IllegalArgumentException("Die Datei konnte nicht geöffnet werden.")
    manager.importProfile(displayName, profileBytes)
}

/** The provider's size metadata is not trusted: enforce the limit while reading. */
internal fun readLimitedWireGuardProfile(stream: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4_096)
    while (true) {
        val read = stream.read(buffer)
        if (read == -1) break
        if (read == 0) continue
        require(output.size() + read <= MAX_WIREGUARD_PROFILE_BYTES) {
            "Die WireGuard-Datei ist zu groß (maximal 64 KiB)."
        }
        output.write(buffer, 0, read)
    }
    require(output.size() >= 20) { "Die WireGuard-Datei ist leer oder unvollständig." }
    return output.toByteArray()
}

internal fun safeWireGuardFilename(name: String?): String =
    name?.substringAfterLast('/')?.substringAfterLast('\\')
        ?.filterNot(Char::isISOControl)
        ?.trim()
        ?.take(60)
        ?.takeIf(String::isNotBlank)
        ?: "WireGuard.conf"

/** Never display provider-controlled exceptions or configuration contents to the user. */
internal fun wireGuardImportErrorMessage(error: Throwable): String = when (error) {
    is SecurityException -> "Kein Zugriff auf die Datei. Bitte im Datei-Explorer erneut freigeben."
    is IllegalArgumentException -> when (error.message) {
        "Die WireGuard-Datei ist zu groß (maximal 64 KiB)." -> error.message!!
        "Die WireGuard-Datei ist leer oder unvollständig." -> error.message!!
        "Die Datei konnte nicht geöffnet werden." -> error.message!!
        "Invalid WireGuard .conf file" -> "Keine gültige WireGuard-Konfiguration."
        else -> "Ungültige WireGuard-Konfiguration. Bitte .conf-Datei und IPv4/IPv6-Routen prüfen."
    }
    else -> "Die Datei konnte nicht importiert werden. Bitte einen anderen Datei-Explorer versuchen."
}
