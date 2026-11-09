package com.nuvio.tv.core.vpn

import android.content.Context
import android.net.VpnService
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class WireGuardProfile(val id: String, val name: String)
data class WireGuardInfo(
    val status: TorrentVpnSafety.Status = TorrentVpnSafety.Status.DISCONNECTED,
    val profile: String? = null,
    val lastHandshake: Long = 0L,
    val receivedBytes: Long = 0L,
    val sentBytes: Long = 0L,
    val exitIp: String? = null,
    val error: String? = null
)

/**
 * Embedded WireGuard; VpnService is supplied by the upstream WireGuard tunnel AAR.
 * All traffic for context.packageName uses the VPN when the tunnel is active.
 * This app-side gate is NOT a kernel kill switch.
 */
@Singleton
class WireGuardManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val safety: TorrentVpnSafety
) {
    private val prefs = context.getSharedPreferences("wireguard_profiles", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directory = File(context.noBackupFilesDir, "wireguard-profiles")
    private val backend by lazy { GoBackend(context.applicationContext) }
    private val tunnel = object : Tunnel {
        override fun getName(): String = "nuvio"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN) {
                safety.update(TorrentVpnSafety.Status.DISCONNECTED)
                publish(status = TorrentVpnSafety.Status.DISCONNECTED, exitIp = null)
            }
        }
    }
    private var monitor: Job? = null
    private val mutableProfiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<WireGuardProfile>> = mutableProfiles.asStateFlow()
    private val mutableInfo = MutableStateFlow(WireGuardInfo())
    val info: StateFlow<WireGuardInfo> = mutableInfo.asStateFlow()

    val selectedId: String?
        get() = prefs.getString("selected", null)

    fun vpnConsentIntent() = VpnService.prepare(context)

    /** Saves sensitive contents in an AES/GCM file protected with an Android Keystore key. */
    suspend fun importProfile(filename: String, raw: ByteArray): WireGuardProfile = withContext(Dispatchers.IO) {
        require(raw.size in 20..65536) { "Invalid WireGuard file size" }
        val parsed = try { Config.parse(ByteArrayInputStream(raw)) } catch (_: Exception) {
            throw IllegalArgumentException("Invalid WireGuard .conf file")
        }
        validateFullTunnel(parsed)
        val id = UUID.randomUUID().toString()
        val name = filename.substringAfterLast('/').take(60).ifBlank { "WireGuard" }
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$id.enc").writeBytes(encrypt(raw))
        val all = prefs.getStringSet("profile_ids", emptySet()).orEmpty().toMutableSet()
        all.add(id)
        prefs.edit()
            .putStringSet("profile_ids", all)
            .putString("name_$id", name)
            .putString("selected", id)
            .apply()
        mutableProfiles.value = loadProfiles()
        WireGuardProfile(id, name)
    }

    suspend fun renameProfile(id: String, name: String) = withContext(Dispatchers.IO) {
        require(id in mutableProfiles.value.map { it.id })
        require(name.isNotBlank() && name.length <= 60 && name.none { it.isISOControl() })
        prefs.edit().putString("name_$id", name.trim()).apply()
        mutableProfiles.value = loadProfiles()
    }

    suspend fun selectProfile(id: String) {
        require(id in mutableProfiles.value.map { it.id })
        disconnect()
        prefs.edit().putString("selected", id).apply()
    }

    suspend fun deleteProfile(id: String) {
        require(id in mutableProfiles.value.map { it.id })
        if (selectedId == id) disconnect()
        withContext(Dispatchers.IO) {
            File(directory, "$id.enc").delete()
            val updated = prefs.getStringSet("profile_ids", emptySet()).orEmpty().toMutableSet()
            updated.remove(id)
            prefs.edit().putStringSet("profile_ids", updated)
                .remove("name_$id")
                .putString("selected", updated.firstOrNull())
                .apply()
            mutableProfiles.value = loadProfiles()
        }
    }

    suspend fun connect(): WireGuardInfo = lock.withLock {
        monitor?.cancel()
        safety.update(TorrentVpnSafety.Status.CONNECTING)
        publish(status = TorrentVpnSafety.Status.CONNECTING, error = null, exitIp = null)
        try {
            check(vpnConsentIntent() == null) { "Android VPN permission is required" }
            val id = selectedId ?: error("Import a WireGuard .conf profile first")
            val bytes = withContext(Dispatchers.IO) { decrypt(File(directory, "$id.enc").readBytes()) }
            val config = Config.parse(ByteArrayInputStream(bytes))
            validateFullTunnel(config)
            val appScoped = restrictToThisApp(config)
            withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, appScoped) }
            safety.update(TorrentVpnSafety.Status.TUNNEL_UP)
            // This app-originated request must leave through the VPN interface, not an ADB shell.
            val exitIp = withContext(Dispatchers.IO) { checkMullvadEgress() }
            val stats = withContext(Dispatchers.IO) { backend.getStatistics(tunnel) }
            val lastHandshake = latestHandshake(stats)
            check(isRecentHandshake(lastHandshake, System.currentTimeMillis())) {
                "No recent WireGuard handshake"
            }
            safety.update(TorrentVpnSafety.Status.VERIFIED)
            publish(
                status = TorrentVpnSafety.Status.VERIFIED,
                profile = mutableProfiles.value.firstOrNull { it.id == id }?.name,
                lastHandshake = lastHandshake,
                receivedBytes = stats.totalRx(),
                sentBytes = stats.totalTx(),
                exitIp = exitIp,
                error = null
            )
            monitor = scope.launch { monitorConnection() }
            mutableInfo.value
        } catch (cancel: CancellationException) {
            safety.update(TorrentVpnSafety.Status.DISCONNECTED)
            throw cancel
        } catch (e: Exception) {
            safety.update(TorrentVpnSafety.Status.ERROR)
            publish(status = TorrentVpnSafety.Status.ERROR, error = friendlyError(e), exitIp = null)
            runCatching { withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) } }
            throw IllegalStateException(friendlyError(e))
        }
    }

    suspend fun disconnect() = lock.withLock {
        safety.update(TorrentVpnSafety.Status.DISCONNECTED)
        monitor?.cancel()
        monitor = null
        withContext(Dispatchers.IO) {
            // A failed disconnect does not re-authorize torrent activity.
            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        }
        publish(status = TorrentVpnSafety.Status.DISCONNECTED, exitIp = null, error = null)
    }

    /** Revalidate current tunnel state without an external connectivity probe. */
    private suspend fun monitorConnection() {
        while (currentCoroutineContext().isActive) {
            delay(10_000)
            if (safety.status.value != TorrentVpnSafety.Status.VERIFIED) break
            try {
                val stats = backend.getStatistics(tunnel)
                val last = latestHandshake(stats)
                check(backend.getState(tunnel) == Tunnel.State.UP && isRecentHandshake(last, System.currentTimeMillis()))
                publish(lastHandshake = last, receivedBytes = stats.totalRx(), sentBytes = stats.totalTx())
            } catch (e: Exception) {
                safety.update(TorrentVpnSafety.Status.ERROR)
                publish(status = TorrentVpnSafety.Status.ERROR, error = "VPN tunnel lost or handshake expired", exitIp = null)
                break
            }
        }
    }

    private fun restrictToThisApp(config: Config): Config {
        val canonical = config.toWgQuickString()
        val withoutApps = canonical.lineSequence()
            .filterNot { it.trimStart().startsWith("IncludedApplications", ignoreCase = true) ||
                it.trimStart().startsWith("ExcludedApplications", ignoreCase = true) }
            .joinToString("\n")
        val scopeOnly = withoutApps.replaceFirst(
            "[Interface]",
            "[Interface]\nIncludedApplications = ${context.packageName}"
        )
        val result = Config.parse(ByteArrayInputStream(scopeOnly.toByteArray(Charsets.UTF_8)))
        check(result.getInterface().includedApplications == setOf(context.packageName))
        return result
    }

    private fun validateFullTunnel(config: Config) {
        require(config.peers.isNotEmpty()) { "WireGuard profile contains no peer" }
        val routes = config.peers.flatMap { it.allowedIps }
        val v4 = routes.any { it.mask == 0 && it.address is Inet4Address }
        val v6 = routes.any { it.mask == 0 && it.address is Inet6Address }
        require(v4 && v6) {
            "Full tunnel requires IPv4 and IPv6 default routes (0.0.0.0/0 and ::/0)"
        }
    }

    private fun checkMullvadEgress(): String {
        val connection = URL("https://am.i.mullvad.net/json").openConnection() as HttpsURLConnection
        connection.connectTimeout = 7_000
        connection.readTimeout = 7_000
        try {
            check(connection.responseCode == 200) { "VPN exit verification unavailable" }
            val response = connection.inputStream.bufferedReader().use { it.readText().take(8192) }
            val json = JSONObject(response)
            check(json.optBoolean("mullvad_exit_ip", false)) { "Connection is not using a Mullvad exit IP" }
            return json.optString("ip", "").take(80)
        } finally {
            connection.disconnect()
        }
    }

    private fun latestHandshake(stats: com.wireguard.android.backend.Statistics): Long =
        stats.peers().mapNotNull { stats.peer(it)?.latestHandshakeEpochMillis() }.maxOrNull() ?: 0L

    private fun isRecentHandshake(last: Long, now: Long): Boolean = last > 0 && last <= now &&
        now - last < 240_000

    private fun loadProfiles(): List<WireGuardProfile> =
        prefs.getStringSet("profile_ids", emptySet()).orEmpty()
            .mapNotNull { id ->
                if (id.matches(Regex("[0-9a-f-]{36}")) && File(directory, "$id.enc").isFile) {
                    WireGuardProfile(id, prefs.getString("name_$id", "WireGuard") ?: "WireGuard")
                } else null
            }.sortedBy { it.name.lowercase() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(clear)
    }

    private fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size > 12 + 16) { "Invalid encrypted VPN profile" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is java.io.FileNotFoundException -> "Selected WireGuard profile is missing"
        is com.wireguard.config.BadConfigException -> "Invalid WireGuard profile"
        is java.net.SocketTimeoutException -> "VPN exit verification timed out"
        is java.net.UnknownHostException -> "VPN DNS lookup failed"
        else -> "Unable to verify WireGuard and Mullvad connectivity"
    }

    private fun publish(
        status: TorrentVpnSafety.Status = mutableInfo.value.status,
        profile: String? = mutableInfo.value.profile,
        lastHandshake: Long = mutableInfo.value.lastHandshake,
        receivedBytes: Long = mutableInfo.value.receivedBytes,
        sentBytes: Long = mutableInfo.value.sentBytes,
        exitIp: String? = mutableInfo.value.exitIp,
        error: String? = mutableInfo.value.error
    ) {
        mutableInfo.value = WireGuardInfo(status, profile, lastHandshake, receivedBytes, sentBytes, exitIp, error)
    }

    companion object {
        private const val KEY_ALIAS = "nuvio_wireguard_profiles_v1"
    }
}
