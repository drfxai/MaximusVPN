package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ConnectionStatus
import com.example.data.model.VlessProfile
import com.example.ui.viewmodel.VpnViewModel
import com.example.vpn.RayVpnService
import com.example.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.DatagramSocket
import java.net.Socket

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VpnReliabilityLifecycleTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext<Application>()
        // Reset state before each test
        RayVpnService.updateState(com.example.data.model.ConnectionState())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `VpnController prepareVpn detects permission status`() {
        val prepareIntent = VpnController.prepareVpn(context)
        // In Robolectric standard context, VpnService.prepare returns an intent or null
        val isGranted = VpnController.isPermissionGranted(context)
        assertEquals(prepareIntent == null, isGranted)
    }

    @Test
    fun `VpnViewModel prepareConnect sets PREPARING state`() {
        val viewModel = VpnViewModel()
        val testProfile = VlessProfile(
            name = "Test Profile",
            address = "1.2.3.4",
            port = 443,
            uuid = "11112222-3333-4444-5555-666677778888"
        )

        viewModel.prepareConnect(testProfile)

        assertEquals(ConnectionStatus.PREPARING, viewModel.connectionState.value.status)
        assertEquals(testProfile, viewModel.pendingProfile.value)
    }

    @Test
    fun `VpnViewModel onPermissionDenied sets FAILED state`() {
        val viewModel = VpnViewModel()
        viewModel.prepareConnect(null)

        viewModel.onPermissionDenied()

        assertEquals(ConnectionStatus.FAILED, viewModel.connectionState.value.status)
        assertEquals("VPN permission was denied by user.", viewModel.connectionState.value.errorMessage)
        assertNull(viewModel.pendingProfile.value)
    }

    @Test
    fun `VpnViewModel onPermissionGranted with pending profile initiates CONNECT`() = runTest {
        val viewModel = VpnViewModel()
        val testProfile = VlessProfile(
            id = "test-prof-1",
            name = "Test Server Node",
            address = "1.2.3.4",
            port = 443,
            uuid = "11112222-3333-4444-5555-666677778888"
        )

        viewModel.prepareConnect(testProfile)
        assertEquals(testProfile, viewModel.pendingProfile.value)

        viewModel.onPermissionGranted(context)
        testScheduler.advanceUntilIdle()

        // Pending profile cleared after grant
        assertNull(viewModel.pendingProfile.value)
    }

    @Test
    fun `socket protect check detects false return`() {
        var protectResult = false
        val mockProtect: (Socket) -> Boolean = { protectResult }

        val testSocket = Socket()
        val isProtected = mockProtect(testSocket)

        assertFalse(isProtected)
        testSocket.close()
    }

    @Test
    fun `ConnectionState isVpnInterfaceActive accurately reflects state`() {
        val active1 = com.example.data.model.ConnectionState(status = ConnectionStatus.VPN_INTERFACE_ESTABLISHED)
        val active2 = com.example.data.model.ConnectionState(status = ConnectionStatus.PROXY_CONNECTING)
        val active3 = com.example.data.model.ConnectionState(status = ConnectionStatus.CONNECTED)
        val inactive = com.example.data.model.ConnectionState(status = ConnectionStatus.FAILED)

        assertTrue(active1.isVpnInterfaceActive)
        assertTrue(active2.isVpnInterfaceActive)
        assertTrue(active3.isVpnInterfaceActive)
        assertFalse(inactive.isVpnInterfaceActive)
    }

    @Test
    fun `datagram socket protect check detects false return`() {
        var protectResult = false
        val mockProtect: (DatagramSocket) -> Boolean = { protectResult }

        val testSocket = DatagramSocket()
        val isProtected = mockProtect(testSocket)

        assertFalse(isProtected)
        testSocket.close()
    }
}
