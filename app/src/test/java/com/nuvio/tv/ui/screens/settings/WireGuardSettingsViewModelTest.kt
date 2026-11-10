package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.vpn.VpnDisplayPreferences
import com.nuvio.tv.core.vpn.WireGuardInfo
import com.nuvio.tv.core.vpn.WireGuardManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WireGuardSettingsViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val manager = mockk<WireGuardManager>()
    private val displayPreferences = mockk<VpnDisplayPreferences>()

    private fun viewModel() = WireGuardSettingsViewModel(manager, displayPreferences)

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
}
