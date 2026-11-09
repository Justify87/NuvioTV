package com.nuvio.tv.core.vpn

import android.content.Context
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
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
    val error: String? = null,
    val endpoint: String? = null,
    val lastVerifiedAt: Long = 0L,
    val lastCheckedAt: Long = 0L,
    val downloadBytesPerSecond: Long = 0L,
    val uploadBytesPerSecond: Long = 0L,
    val exitCountry: String? = null,
    val exitCity: String? = null
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
    @Volatile private var observedNetwork: Network? = null
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = observedNetwork
            observedNetwork = network
            if (previous != null && previous != network) invalidateForNetworkChange()
        }

        override fun onLost(network: Network) {
            if (observedNetwork == network) observedNetwork = null
            invalidateForNetworkChange()
        }
    }
    private val directory = File(context.noBackupFilesDir, "wireguard-profiles")
    private val backend by lazy { GoBackend(context.applicationContext) }
    private val tunnel = object : Tunnel {
        override fun getName(): String = "nuvio"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && safety.status.value != TorrentVpnSafety.Status.ERROR) {
                safety.update(TorrentVpnSafety.Status.DISCONNECTED)
                publish(status = TorrentVpnSafety.Status.DISCONNECTED, exitIp = null,
                    lastVerifiedAt = 0L, downloadBytesPerSecond = 0L, uploadBytesPerSecond = 0L)
            }
        }
    }
    private var monitor: Job? = null
    init {
        // Tracks Android's default network as seen by this VPN-included app UID.
        // A network transition invalidates the verified egress state immediately.
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
    }

    private fun invalidateForNetworkChange() {
        if (!safety.isReady()) return
        safety.update(TorrentVpnSafety.Status.ERROR)
        publish(
            status = TorrentVpnSafety.Status.ERROR,
            error = "Netzwerk gewechselt oder verloren: VPN erneut verbinden",
            exitIp = null,
            lastVerifiedAt = 0L,
            downloadBytesPerSecond = 0L,
            uploadBytesPerSecond = 0L
        )
        monitor?.cancel()
        scope.launch {
            lock.withLock {
                if (safety.status.value == TorrentVpnSafety.Status.ERROR) {
                    runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                }
            }
        }
    }
    private val mutableProfiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<WireGuardProfile>> = mutableProfiles.asStateFlow()
    private val mutableInfo = MutableStateFlow(WireGuardInfo())
    val info: StateFlow<WireGuardInfo> = mutableInfo.asStateFlow()
    private val mutableTraffic = MutableStateFlow<List<VpnTrafficSample>>(emptyList())
    val traffic: StateFlow<List<VpnTrafficSample>> = mutableTraffic.asStateFlow()
    private var previousSampleAt = 0L
    private var previousRx = 0L
    private var previousTx = 0L

    val selectedId: String?
        get() = prefs.getString("selected", null)

    var autoConnectEnabled: Boolean
        get() = prefs.getBoolean("auto_connect", false)
        set(value) { prefs.edit().putBoolean("auto_connect", value).apply() }

    fun vpnConsentIntent() = VpnService.prepare(context)

    /** Saves sensitive contents in an AES/GCM file protected with an Android Keystore key. */
    suspend fun importProfile(filename: String, raw: ByteArray): WireGuardProfile = withContext(Dispatchers.IO) {
        require(raw.size in 20..65536) { "Invalid WireGuard file size" }
        val parsed = try { Config.parse(ByteArrayInputStream(raw)) } catch (_: Exception) {
            throw IllegalArgumentException("Invalid WireGuard .conf file")
        }
        VpnRoutePolicy.requireDualStackFullTunnel(parsed)
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
        mutableTraffic.value = emptyList()
        previousSampleAt = 0L
        safety.update(TorrentVpnSafety.Status.CONNECTING)
        publish(status = TorrentVpnSafety.Status.CONNECTING, error = null, exitIp = null)
        try {
            check(vpnConsentIntent() == null) { "Android VPN permission is required" }
            val id = selectedId ?: error("Import a WireGuard .conf profile first")
            val bytes = withContext(Dispatchers.IO) { decrypt(File(directory, "$id.enc").readBytes()) }
            val config = Config.parse(ByteArrayInputStream(bytes))
            VpnRoutePolicy.requireDualStackFullTunnel(config)
            val appScoped = VpnRoutePolicy.restrictToApplication(config, context.packageName)
            withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, appScoped) }
            safety.update(TorrentVpnSafety.Status.TUNNEL_UP)
            // This app-originated request must leave through the VPN interface, not an ADB shell.
            val verifiedExit = withContext(Dispatchers.IO) { checkMullvadEgress() }
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
                exitIp = verifiedExit.ip,
                exitCountry = verifiedExit.country,
                exitCity = verifiedExit.city,
                endpoint = config.toWgQuickString().lineSequence()
                    .firstOrNull { it.trimStart().startsWith("Endpoint", ignoreCase = true) }
                    ?.substringAfter('=')?.trim(),
                lastVerifiedAt = System.currentTimeMillis(),
                lastCheckedAt = System.currentTimeMillis(),
                error = null
            )
            monitor = scope.launch { monitorConnection() }
            mutableInfo.value
        } catch (cancel: CancellationException) {
            safety.update(TorrentVpnSafety.Status.DISCONNECTED)
            throw cancel
        } catch (e: Exception) {
            safety.update(TorrentVpnSafety.Status.ERROR)
            publish(status = TorrentVpnSafety.Status.ERROR, error = friendlyError(e), exitIp = null,
                lastVerifiedAt = 0L, downloadBytesPerSecond = 0L, uploadBytesPerSecond = 0L)
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
        publish(status = TorrentVpnSafety.Status.DISCONNECTED, exitIp = null, error = null,
            lastVerifiedAt = 0L, endpoint = null, exitCountry = null, exitCity = null,
            downloadBytesPerSecond = 0L, uploadBytesPerSecond = 0L)
        mutableTraffic.value = emptyList()
        previousSampleAt = 0L
    }

    /** User-requested egress verification: never reconnects or changes the selected profile. */
    suspend fun refreshVerification(): WireGuardInfo = lock.withLock {
        check(safety.isReady()) { "VPN is not verified; connect it in settings first" }
        try {
            verifyEgress()
            mutableInfo.value
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            failClosed("VPN egress verification failed")
            throw IllegalStateException("VPN egress verification failed", error)
        }
    }

    private suspend fun verifyEgress() {
        check(backend.getState(tunnel) == Tunnel.State.UP) { "VPN tunnel is down" }
        check(vpnConsentIntent() == null) { "Android VPN consent was revoked" }
        val verifiedExit = withContext(Dispatchers.IO) { checkMullvadEgress() }
        check(backend.getState(tunnel) == Tunnel.State.UP) { "VPN tunnel stopped during verification" }
        val stats = backend.getStatistics(tunnel)
        check(isRecentHandshake(latestHandshake(stats), System.currentTimeMillis())) {
            "No recent WireGuard handshake after the egress probe"
        }
        val now = System.currentTimeMillis()
        publish(exitIp = verifiedExit.ip, exitCountry = verifiedExit.country,
            exitCity = verifiedExit.city, lastHandshake = latestHandshake(stats),
            receivedBytes = stats.totalRx(), sentBytes = stats.totalTx(),
            lastVerifiedAt = now, lastCheckedAt = now, error = null)
    }

    private suspend fun failClosed(message: String) {
        safety.update(TorrentVpnSafety.Status.ERROR)
        publish(status = TorrentVpnSafety.Status.ERROR, error = message,
            exitIp = null, lastVerifiedAt = 0L,
            downloadBytesPerSecond = 0L, uploadBytesPerSecond = 0L)
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
    }

    /** Lightweight counters plus periodic HTTPS egress verification. */
    private suspend fun monitorConnection() {
        while (currentCoroutineContext().isActive) {
            delay(5_000)
            if (safety.status.value != TorrentVpnSafety.Status.VERIFIED) break
            try {
                check(backend.getState(tunnel) == Tunnel.State.UP) { "WireGuard tunnel is down" }
                check(vpnConsentIntent() == null) { "Android VPN consent was revoked" }
                val stats = backend.getStatistics(tunnel)
                val nowElapsed = android.os.SystemClock.elapsedRealtime()
                val nowWall = System.currentTimeMillis()
                val rx = stats.totalRx()
                val tx = stats.totalTx()
                val interval = nowElapsed - previousSampleAt
                val down = if (previousSampleAt > 0L && interval > 0L && rx >= previousRx)
                    (rx - previousRx) * 1000L / interval else 0L
                val up = if (previousSampleAt > 0L && interval > 0L && tx >= previousTx)
                    (tx - previousTx) * 1000L / interval else 0L
                previousSampleAt = nowElapsed
                previousRx = rx
                previousTx = tx
                mutableTraffic.value = (mutableTraffic.value +
                    VpnTrafficSample(nowWall, down, up)).takeLast(12)
                publish(lastHandshake = latestHandshake(stats), receivedBytes = rx, sentBytes = tx,
                    lastCheckedAt = nowWall, downloadBytesPerSecond = down, uploadBytesPerSecond = up)
                // The last handshake may be old on an idle tunnel; test actual egress
                // instead of treating a quiet connection as a failure.
                if (nowWall - mutableInfo.value.lastVerifiedAt >= 60_000L) {
                    verifyEgress()
                }
                check(nowWall - mutableInfo.value.lastVerifiedAt <= 90_000L) {
                    "VPN egress verification has expired"
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                failClosed("VPN tunnel or verified egress unavailable")
                break
            }
        }
    }

    private data class MullvadExit(val ip: String, val country: String?, val city: String?)

    private fun checkMullvadEgress(): MullvadExit {
        val connection = URL("https://am.i.mullvad.net/json").openConnection() as HttpsURLConnection
        connection.connectTimeout = 7_000
        connection.readTimeout = 7_000
        try {
            check(connection.responseCode == 200) { "VPN exit verification unavailable" }
            val response = connection.inputStream.bufferedReader().use { it.readText().take(8192) }
            val json = JSONObject(response)
            check(json.optBoolean("mullvad_exit_ip", false)) { "Connection is not using a Mullvad exit IP" }
            val ip = json.optString("ip", "").trim().take(80)
            check(ip.isNotEmpty()) { "Mullvad response contained no exit IP" }
            return MullvadExit(
                ip = ip,
                country = json.optString("country", "").trim().take(60).ifBlank { null },
                city = json.optString("city", "").trim().take(60).ifBlank { null }
            )
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
        error: String? = mutableInfo.value.error,
        endpoint: String? = mutableInfo.value.endpoint,
        lastVerifiedAt: Long = mutableInfo.value.lastVerifiedAt,
        lastCheckedAt: Long = mutableInfo.value.lastCheckedAt,
        downloadBytesPerSecond: Long = mutableInfo.value.downloadBytesPerSecond,
        uploadBytesPerSecond: Long = mutableInfo.value.uploadBytesPerSecond,
        exitCountry: String? = mutableInfo.value.exitCountry,
        exitCity: String? = mutableInfo.value.exitCity
    ) {
        mutableInfo.value = WireGuardInfo(status, profile, lastHandshake, receivedBytes,
            sentBytes, exitIp, error, endpoint, lastVerifiedAt, lastCheckedAt,
            downloadBytesPerSecond, uploadBytesPerSecond, exitCountry, exitCity)
    }

    companion object {
        private const val KEY_ALIAS = "nuvio_wireguard_profiles_v1"
    }
}
