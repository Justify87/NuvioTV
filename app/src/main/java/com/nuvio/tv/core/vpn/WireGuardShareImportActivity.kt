package com.nuvio.tv.core.vpn

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Explicit confirmation for a profile shared from a TV-optimized file explorer
 * (e.g. FX). No automatic import: an incoming Intent can originate from any app.
 * A single read-granted content URI is consumed immediately; raw configs are
 * never put in Intent extras, logs, files, or the UI.
 */
@AndroidEntryPoint
class WireGuardShareImportActivity : ComponentActivity() {

    @Inject lateinit var manager: WireGuardManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceUri = extractSharedProfileUri(intent)
        if (sourceUri == null) {
            finish()
            return
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SharedProfileConfirmation(
                        onImport = { importWireGuardProfileFromUri(applicationContext, manager, sourceUri) },
                        onClose = { finish() }
                    )
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun extractSharedProfileUri(intent: Intent?): Uri? {
    if (intent?.action != Intent.ACTION_SEND) return null
    val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        ?: intent.clipData?.let { if (it.itemCount > 0) it.getItemAt(0).uri else null }
    return stream?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
}

@Composable
private fun SharedProfileConfirmation(
    onImport: suspend () -> WireGuardProfile,
    onClose: () -> Unit
) {
    var importing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val importFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(180)
        runCatching { importFocus.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = { if (!importing) onClose() },
        title = { Text("Nuvio TV VPN – WireGuard-Import") },
        text = {
            Text(
                result
                    ?: "Eine Datei wurde aus dem Datei-Explorer freigegeben. " +
                       "Soll die WireGuard-Konfiguration geprüft und verschlüsselt gespeichert werden? " +
                       "Die VPN-Verbindung wird dadurch noch nicht gestartet."
            )
        },
        confirmButton = {
            TextButton(
                enabled = !importing,
                modifier = Modifier.focusRequester(importFocus),
                onClick = {
                    if (result != null) {
                        onClose()
                    } else {
                        importing = true
                        scope.launch {
                            try {
                                onImport()
                                result = "WireGuard-Profil importiert. Öffne VPN & WireGuard, um die Verbindung zu starten."
                            } catch (error: Exception) {
                                result = "Import fehlgeschlagen: ${wireGuardImportErrorMessage(error)}"
                            } finally {
                                importing = false
                            }
                        }
                    }
                }
            ) {
                Text(if (result != null) "Schließen" else if (importing) "Importiere…" else "Importieren")
            }
        },
        dismissButton = {
            if (result == null) {
                TextButton(enabled = !importing, onClick = onClose) {
                    Text("Abbrechen")
                }
            }
        }
    )
}
