package com.nuvio.tv.ui.screens.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.core.vpn.TorrentVpnSafety
import com.nuvio.tv.core.vpn.importWireGuardProfileFromUri
import com.nuvio.tv.core.vpn.wireGuardImportErrorMessage
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

/** TV remote-first WireGuard controls; no other VPN app is required. */
@Composable
internal fun WireGuardSettingsContent(
    initialFocusRequester: FocusRequester?,
    viewModel: WireGuardSettingsViewModel = hiltViewModel()
) {
    val vpn = viewModel.wireGuard
    val displayPrefs = viewModel.displayPreferences
    val display by displayPrefs.state.collectAsStateWithLifecycle()
    val traffic by vpn.traffic.collectAsStateWithLifecycle()
    var statusDialogOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info by vpn.info.collectAsStateWithLifecycle()
    val profiles by vpn.profiles.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(vpn.selectedId) }
    // An external file manager can import a profile while this screen is paused.
    // Refresh the selection whenever WireGuardManager publishes a new profile list.
    LaunchedEffect(profiles) { selected = vpn.selectedId }
    var autoConnect by remember { mutableStateOf(vpn.autoConnectEnabled) }
    var message by remember { mutableStateOf<String?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    fun connect() {
        scope.launch {
            runCatching { vpn.connect() }
                .onSuccess { message = null }
                .onFailure { message = it.message ?: "VPN connection failed" }
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK && VpnService.prepare(context) == null) connect()
        else message = "VPN permission was not granted"
    }
    fun importSelectedUri(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            try {
                val profile = importWireGuardProfileFromUri(context, vpn, uri)
                selected = profile.id
                message = "WireGuard-Profil importiert. VPN verbinden, um es zu aktivieren."
            } catch (error: Exception) {
                message = "Import fehlgeschlagen: ${wireGuardImportErrorMessage(error)}"
            }
        }
    }

    // ACTION_GET_CONTENT may expose compatible third-party file pickers such as
    // FX in the Android chooser. The OS decides which installed apps appear.
    val browserImporter = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: result.data?.clipData?.let {
                if (it.itemCount > 0) it.getItemAt(0).uri else null
            }
            importSelectedUri(uri)
        }
    }

    // Preserve the original Android DocumentsUI path as an explicit fallback.
    val systemImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importSelectedUri(uri)
    }

    if (statusDialogOpen) {
        com.nuvio.tv.ui.components.VpnStatusDialog(
            info = info,
            traffic = traffic,
            onDismiss = { statusDialogOpen = false },
            onRefresh = { scope.launch { runCatching { vpn.refreshVerification() } } }
        )
    }
    if (renameId != null) {
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text("Rename WireGuard profile") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(60) },
                    label = { Text("Profile name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = renameId
                    renameId = null
                    if (id != null) scope.launch {
                        runCatching { vpn.renameProfile(id, renameText) }
                            .onFailure { message = it.message }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("Cancel") } }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        item {
            SettingsDetailHeader(
                title = "VPN & WireGuard",
                subtitle = "Nur Nuvio benutzt den Mullvad-VPN-Tunnel. Andere Shield-Apps bleiben unverändert."
            )
        }
        item {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth(), title = "Verbindung") {
                SettingsActionRow(
                    title = "Status: ${info.status.name}",
                    subtitle = when (info.status) {
                        TorrentVpnSafety.Status.VERIFIED -> "Mullvad-Ausgang und aktueller WireGuard-Handshake bestätigt"
                        TorrentVpnSafety.Status.TUNNEL_UP -> "Tunnel gestartet; VPN-Ausgang noch nicht bestätigt"
                        TorrentVpnSafety.Status.CONNECTING -> "Tunnel wird gestartet und geprüft"
                        TorrentVpnSafety.Status.ERROR -> "VPN-Fehler: Torrent-Verbindungen gesperrt"
                        else -> "Nicht verbunden: Torrent-Verbindungen gesperrt"
                    },
                    leadingIcon = Icons.Default.Security,
                    onClick = { /* informational row; remote can focus it */ }
                )
                SettingsActionRow(
                    title = if (info.status == TorrentVpnSafety.Status.VERIFIED) "Erneut überprüfen" else "VPN verbinden",
                    subtitle = "Erfordert Android-VPN-Berechtigung und eine Mullvad-WireGuard-Datei",
                    onClick = {
                        val request = vpn.vpnConsentIntent()
                        if (request != null) consent.launch(request) else connect()
                    },
                    modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier
                )
                SettingsToggleRow(
                    title = "Beim App-Start automatisch verbinden",
                    subtitle = "Verbindet das ausgewählte Profil nach einem Neustart erneut, sofern Android die VPN-Berechtigung noch erlaubt",
                    checked = autoConnect,
                    onToggle = {
                        autoConnect = !autoConnect
                        vpn.autoConnectEnabled = autoConnect
                    }
                )
                SettingsActionRow(
                    title = "VPN trennen",
                    subtitle = "Stoppt und sperrt alle Nuvio-Torrents",
                    onClick = { scope.launch { vpn.disconnect() } }
                )
                if (info.lastHandshake > 0) {
                    SettingsNote("Letzter Handshake: ${DateFormat.getDateTimeInstance().format(Date(info.lastHandshake))}")
                }
                if (info.exitIp != null) SettingsNote("Verifizierte Mullvad-Ausgangs-IP: ${info.exitIp}")
                if (info.status == TorrentVpnSafety.Status.VERIFIED) {
                    SettingsNote("Empfangen: ${info.receivedBytes / 1024} KiB · Gesendet: ${info.sentBytes / 1024} KiB")
                }
            }
        }
        item {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth(), title = "VPN-Statusanzeige") {
                SettingsToggleRow(
                    title = "VPN-Status im Hauptmenü",
                    subtitle = "Kleines Schildsymbol auf der Startseite",
                    checked = display.showHomeIndicator,
                    onToggle = { displayPrefs.setHomeIndicator(!display.showHomeIndicator) }
                )
                SettingsActionRow(
                    title = "Anzeige im Player",
                    value = display.mode.label,
                    subtitle = "Aus, nur bei Störung oder immer",
                    onClick = { displayPrefs.cycleMode() }
                )
                SettingsActionRow(
                    title = "Position im Player",
                    value = display.position.label,
                    onClick = { displayPrefs.cyclePosition() }
                )
                SettingsActionRow(
                    title = "Größe im Player",
                    value = display.size.label,
                    onClick = { displayPrefs.cycleSize() }
                )
                SettingsActionRow(
                    title = "WireGuard-Netzwerkdiagnose",
                    subtitle = "Datenraten, Tunnel-Endpunkt und Messverlauf",
                    onClick = { statusDialogOpen = true }
                )
            }
        }
        item {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth(), title = "WireGuard-Profile") {
                SettingsActionRow(
                    title = "WireGuard-Konfiguration importieren",
                    subtitle = "Datei-Explorer auswählen (z. B. FX, falls von Android angeboten)",
                    onClick = {
                        val selection = Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            browserImporter.launch(Intent.createChooser(selection, "Datei-Explorer auswählen"))
                        } catch (_: ActivityNotFoundException) {
                            message = "Kein passender Datei-Explorer gefunden. Android-Dateidialog geöffnet."
                            systemImporter.launch(arrayOf("*/*"))
                        }
                    }
                )
                SettingsActionRow(
                    title = "Mit Android-Dateidialog importieren",
                    subtitle = "Alternative, falls ein anderer Datei-Explorer nicht angeboten wird",
                    onClick = { systemImporter.launch(arrayOf("*/*")) }
                )
                SettingsNote("Alternativ: .conf-Datei in FX File Explorer öffnen und über Teilen → Nuvio TV VPN importieren.")
            }
        }
        items(profiles, key = { it.id }) { profile ->
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsActionRow(
                    title = (if (selected == profile.id) "✓ " else "") + profile.name,
                    subtitle = "Profil auswählen (trennt eine aktive Verbindung)",
                    onClick = {
                        scope.launch {
                            runCatching { vpn.selectProfile(profile.id) }
                                .onSuccess { selected = profile.id }
                                .onFailure { message = it.message }
                        }
                    }
                )
                SettingsActionRow(
                    title = "Umbenennen",
                    subtitle = "Anzeigename ändern",
                    onClick = {
                        renameId = profile.id
                        renameText = profile.name
                    }
                )
                SettingsActionRow(
                    title = "Profil löschen",
                    subtitle = "Verschlüsselte Datei dauerhaft aus dem App-Speicher entfernen",
                    onClick = {
                        scope.launch {
                            runCatching { vpn.deleteProfile(profile.id) }
                                .onSuccess { selected = vpn.selectedId }
                                .onFailure { message = it.message }
                        }
                    }
                )
            }
        }
        item {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth(), title = "Split-Tunneling & Sicherheit") {
                SettingsNote("VPN-Routing: ausschließlich diese Nuvio-App. IPv4- und IPv6-Default-Routen sind Pflicht.")
                SettingsNote("App-interne Torrent-Sperre: immer aktiv. Keine direkte Torrent-Ausweichverbindung.")
                SettingsNote("Kein systemweiter Kill-Switch. Bei VPN-Prozessabsturz kann Android Netzwerkpakete ohne VPN senden. Die Torrent-Engine wird bei erkannten Fehlern geschlossen.")
                SettingsNote("Ein erfolgreicher Verbindungstest ersetzt keinen Leak-Test auf der Shield.")
            }
        }
        if (message != null || info.error != null) {
            item {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth(), title = "Hinweis") {
                    SettingsNote(message ?: info.error.orEmpty())
                }
            }
        }
    }
}
