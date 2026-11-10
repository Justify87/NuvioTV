package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.net.Uri
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.vpn.VpnDisplayPreferences
import com.nuvio.tv.core.vpn.WireGuardProfile
import com.nuvio.tv.core.vpn.importWireGuardProfileFromUri
import com.nuvio.tv.core.vpn.WireGuardInfo
import com.nuvio.tv.core.vpn.WireGuardManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WireGuardSettingsViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val manager = mockk<WireGuardManager>()
    private val displayPreferences = mockk<VpnDisplayPreferences>()
    private val context = mockk<Context>()
    private val profiles = MutableStateFlow<List<WireGuardProfile>>(emptyList())
    private var managerSelectedId: String? = null

    @Before
    fun setUp() {
        every { manager.selectedId } answers { managerSelectedId }
        every { manager.profiles } returns profiles
    }

    private fun viewModel() = WireGuardSettingsViewModel(manager, displayPreferences, context)

    @Test
    fun connectRunsManagerActionAndClearsBusyState() = runTest {
        coEvery { manager.connect() } returns WireGuardInfo()
        val viewModel = viewModel()

        viewModel.connect()
        assertTrue(viewModel.uiState.value.actionInProgress)
        runCurrent()

        coVerify(exactly = 1) { manager.connect() }
        assertEquals(WireGuardSettingsUiState(), viewModel.uiState.value)
    }

    @Test
    fun disconnectRunsManagerActionAndClearsBusyState() = runTest {
        coEvery { manager.disconnect() } returns Unit
        val viewModel = viewModel()

        viewModel.disconnect()
        assertTrue(viewModel.uiState.value.actionInProgress)
        runCurrent()

        coVerify(exactly = 1) { manager.disconnect() }
        assertEquals(WireGuardSettingsUiState(), viewModel.uiState.value)
    }

    @Test
    fun refreshReportsSuccessWithoutReconnecting() = runTest {
        coEvery { manager.refreshVerification() } returns WireGuardInfo()
        val viewModel = viewModel()

        viewModel.refreshVerification()
        runCurrent()

        coVerify(exactly = 1) { manager.refreshVerification() }
        coVerify(exactly = 0) { manager.connect() }
        assertFalse(viewModel.uiState.value.actionInProgress)
        assertEquals("VPN-Verbindung erfolgreich überprüft", viewModel.uiState.value.message)
    }

    @Test
    fun errorsAreDisplayedAndDoNotBlockAnotherAction() = runTest {
        coEvery { manager.connect() } throws IllegalStateException("No WireGuard profile")
        coEvery { manager.disconnect() } returns Unit
        val viewModel = viewModel()

        viewModel.connect()
        runCurrent()
        assertFalse(viewModel.uiState.value.actionInProgress)
        assertEquals("No WireGuard profile", viewModel.uiState.value.message)

        viewModel.disconnect()
        assertTrue(viewModel.uiState.value.actionInProgress)
        assertEquals(null, viewModel.uiState.value.message)
        runCurrent()
        coVerify(exactly = 1) { manager.disconnect() }
        assertEquals(WireGuardSettingsUiState(), viewModel.uiState.value)
    }

    @Test
    fun repeatedTapsDoNotQueueActionsWhileConnecting() = runTest {
        val releaseConnect = CompletableDeferred<Unit>()
        coEvery { manager.connect() } coAnswers {
            releaseConnect.await()
            WireGuardInfo()
        }
        val viewModel = viewModel()

        viewModel.connect()
        viewModel.connect()
        viewModel.disconnect()
        viewModel.refreshVerification()
        runCurrent()

        assertTrue(viewModel.uiState.value.actionInProgress)
        coVerify(exactly = 1) { manager.connect() }
        coVerify(exactly = 0) { manager.disconnect() }
        coVerify(exactly = 0) { manager.refreshVerification() }

        releaseConnect.complete(Unit)
        runCurrent()
        assertEquals(WireGuardSettingsUiState(), viewModel.uiState.value)
    }
    @Test
    fun selectProfileUpdatesSelectionAfterSuccess() = runTest {
        coEvery { manager.selectProfile("profile-a") } coAnswers {
            managerSelectedId = "profile-a"
        }
        val viewModel = viewModel()

        viewModel.selectProfile("profile-a")
        assertTrue(viewModel.uiState.value.actionInProgress)
        runCurrent()

        coVerify(exactly = 1) { manager.selectProfile("profile-a") }
        assertEquals("profile-a", viewModel.selectedProfileId.value)
        assertEquals(WireGuardSettingsUiState(), viewModel.uiState.value)
    }

    @Test
    fun selectProfileFailurePreservesSelectionAndReportsError() = runTest {
        coEvery { manager.selectProfile("missing") } throws IllegalArgumentException("Unknown profile")
        val viewModel = viewModel()

        viewModel.selectProfile("missing")
        runCurrent()

        assertEquals(null, viewModel.selectedProfileId.value)
        assertFalse(viewModel.uiState.value.actionInProgress)
        assertEquals("Unknown profile", viewModel.uiState.value.message)
    }

    @Test
    fun renameProfileDelegatesAndClearsBusyState() = runTest {
        coEvery { manager.renameProfile("profile-a", "Renamed") } returns Unit
        val viewModel = viewModel()

        viewModel.renameProfile("profile-a", "Renamed")
        runCurrent()

        coVerify(exactly = 1) { manager.renameProfile("profile-a", "Renamed") }
        assertEquals(WireGuardSettingsUiState(), viewModel.uiState.value)
    }

    @Test
    fun renameProfileFailureReportsError() = runTest {
        coEvery { manager.renameProfile("profile-a", "") } throws IllegalArgumentException("Invalid name")
        val viewModel = viewModel()

        viewModel.renameProfile("profile-a", "")
        runCurrent()

        assertFalse(viewModel.uiState.value.actionInProgress)
        assertEquals("Invalid name", viewModel.uiState.value.message)
    }

    @Test
    fun deleteProfileUpdatesSelectedFallback() = runTest {
        managerSelectedId = "profile-a"
        coEvery { manager.deleteProfile("profile-a") } coAnswers {
            managerSelectedId = "profile-b"
        }
        val viewModel = viewModel()

        viewModel.deleteProfile("profile-a")
        runCurrent()

        coVerify(exactly = 1) { manager.deleteProfile("profile-a") }
        assertEquals("profile-b", viewModel.selectedProfileId.value)
        assertEquals(WireGuardSettingsUiState(), viewModel.uiState.value)
    }

    @Test
    fun deleteProfileFailurePreservesSelection() = runTest {
        managerSelectedId = "profile-a"
        coEvery { manager.deleteProfile("profile-a") } throws IllegalStateException("Delete failed")
        val viewModel = viewModel()

        viewModel.deleteProfile("profile-a")
        runCurrent()

        assertEquals("profile-a", viewModel.selectedProfileId.value)
        assertFalse(viewModel.uiState.value.actionInProgress)
        assertEquals("Delete failed", viewModel.uiState.value.message)
    }

    @Test
    fun importProfileDelegatesContentUriAndSelectsImportedProfile() = runTest {
        val uri = mockk<Uri>()
        mockkStatic(::importWireGuardProfileFromUri)
        try {
            coEvery { importWireGuardProfileFromUri(context, manager, uri) } returns
                WireGuardProfile("imported", "Home")
            val viewModel = viewModel()

            viewModel.importProfile(uri)
            runCurrent()

            coVerify(exactly = 1) { importWireGuardProfileFromUri(context, manager, uri) }
            assertEquals("imported", viewModel.selectedProfileId.value)
            assertFalse(viewModel.uiState.value.actionInProgress)
            assertEquals(
                "WireGuard-Profil importiert. VPN verbinden, um es zu aktivieren.",
                viewModel.uiState.value.message
            )
        } finally {
            unmockkStatic(::importWireGuardProfileFromUri)
        }
    }

    @Test
    fun importProfileFailureDisplaysSanitizedImportError() = runTest {
        val uri = mockk<Uri>()
        mockkStatic(::importWireGuardProfileFromUri)
        try {
            coEvery { importWireGuardProfileFromUri(context, manager, uri) } throws
                IllegalArgumentException("Invalid WireGuard .conf file")
            val viewModel = viewModel()

            viewModel.importProfile(uri)
            runCurrent()

            assertEquals(null, viewModel.selectedProfileId.value)
            assertFalse(viewModel.uiState.value.actionInProgress)
            assertEquals(
                "Import fehlgeschlagen: Keine gültige WireGuard-Konfiguration.",
                viewModel.uiState.value.message
            )
        } finally {
            unmockkStatic(::importWireGuardProfileFromUri)
        }
    }

    @Test
    fun externalImportRefreshesSelectedProfileWhenManagerPublishesProfiles() = runTest {
        val viewModel = viewModel()
        runCurrent()
        managerSelectedId = "external"
        profiles.value = listOf(WireGuardProfile("external", "External"))
        runCurrent()

        assertEquals("external", viewModel.selectedProfileId.value)
    }

}
