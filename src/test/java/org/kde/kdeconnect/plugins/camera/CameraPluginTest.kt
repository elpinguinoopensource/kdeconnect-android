/*
 * SPDX-FileCopyrightText: 2026 Alfredo Medrano Sanchez <alfredomedranosanchez@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.camera

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect_tp.R

class CameraPluginTest {

    private lateinit var plugin: CameraPlugin
    private lateinit var device: Device
    private lateinit var context: Context

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any()) } returns 0

        mockkStatic(Looper::class)
        val mockLooper = mockk<Looper>(relaxed = true)
        every { Looper.getMainLooper() } returns mockLooper

        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED

        context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>()
            every { getString(R.string.cameraplugin_title) } returns "Camera as webcam"
            every { getString(R.string.cameraplugin_description) } returns "Share this device's camera with the connected computer"
        }
        device = mockk<Device> {
            every { sendPacket(any()) } returns Unit
        }

        plugin = CameraPlugin()
        plugin.setContext(context, device)
        // Inject a handler that runs Runnables synchronously for testing
        plugin.mainHandler = mockk<Handler>(relaxed = true) {
            every { post(any()) } answers {
                firstArg<Runnable>().run()
                true
            }
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun supportedPacketTypesContainsExpectedTypes() {
        val supported = plugin.supportedPacketTypes.toList()
        assertTrue(supported.contains(CameraProtocol.PACKET_TYPE_CAMERA_LIST))
        assertTrue(supported.contains(CameraProtocol.PACKET_TYPE_CAMERA_START))
        assertTrue(supported.contains(CameraProtocol.PACKET_TYPE_CAMERA_STOP))
        assertTrue(supported.contains(CameraProtocol.PACKET_TYPE_CAMERA_STATS))
        assertEquals(4, supported.size)
    }

    @Test
    fun outgoingPacketTypesContainsExpectedTypes() {
        val outgoing = plugin.outgoingPacketTypes.toList()
        assertTrue(outgoing.contains(CameraProtocol.PACKET_TYPE_CAMERA_LIST))
        assertTrue(outgoing.contains(CameraProtocol.PACKET_TYPE_CAMERA_STREAM))
        assertTrue(outgoing.contains(CameraProtocol.PACKET_TYPE_CAMERA_ERROR))
        assertEquals(3, outgoing.size)
    }

    @Test
    fun sendCameraListSerializesInjectedCatalog() {
        val fakeCatalog = object : CameraCatalog {
            override fun listCameras(): List<CameraDescription> {
                return listOf(
                    CameraDescription(
                        id = "0",
                        facing = CameraProtocol.FACING_BACK,
                        hasFlash = true,
                        sizes = listOf(CameraSize(1920, 1080, 30), CameraSize(1280, 720, 60))
                    ),
                    CameraDescription(
                        id = "1",
                        facing = CameraProtocol.FACING_FRONT,
                        hasFlash = false,
                        sizes = listOf(CameraSize(1280, 720, 30))
                    )
                )
            }
        }
        plugin.catalogProvider = { fakeCatalog }

        val packetSlot = slot<NetworkPacket>()
        every { device.sendPacket(capture(packetSlot)) } returns Unit

        plugin.sendCameraList()

        verify(exactly = 1) { device.sendPacket(any()) }

        val sentPacket = packetSlot.captured
        assertEquals(CameraProtocol.PACKET_TYPE_CAMERA_LIST, sentPacket.type)

        val camerasArray = sentPacket.getJSONArray(CameraProtocol.KEY_CAMERAS)
        assertEquals(2, camerasArray!!.length())

        // Verify first camera
        val cam0 = camerasArray.getJSONObject(0)
        assertEquals("0", cam0.getString(CameraProtocol.KEY_CAMERA_ID))
        assertEquals(CameraProtocol.FACING_BACK, cam0.getString(CameraProtocol.KEY_FACING))
        assertTrue(cam0.getBoolean(CameraProtocol.KEY_HAS_FLASH))

        val sizes0 = cam0.getJSONArray(CameraProtocol.KEY_SIZES)
        assertEquals(2, sizes0!!.length())
        val size0_0 = sizes0.getJSONObject(0)
        assertEquals(1920, size0_0.getInt(CameraProtocol.KEY_WIDTH))
        assertEquals(1080, size0_0.getInt(CameraProtocol.KEY_HEIGHT))
        assertEquals(30, size0_0.getInt(CameraProtocol.KEY_FPS))

        // Verify second camera
        val cam1 = camerasArray.getJSONObject(1)
        assertEquals("1", cam1.getString(CameraProtocol.KEY_CAMERA_ID))
        assertEquals(CameraProtocol.FACING_FRONT, cam1.getString(CameraProtocol.KEY_FACING))
        assertFalse(cam1.getBoolean(CameraProtocol.KEY_HAS_FLASH))
    }

    @Test
    fun onPacketReceivedReturnsFalseForUnrelatedType() {
        val np = NetworkPacket("kdeconnect.someother.type")
        assertFalse(plugin.onPacketReceived(np))
    }

    @Test
    fun unavailableCameraCatalogYieldsEmptyArray() {
        // Explicitly inject UnavailableCameraCatalog to test its behavior
        plugin.catalogProvider = { UnavailableCameraCatalog() }
        val packetSlot = slot<NetworkPacket>()
        every { device.sendPacket(capture(packetSlot)) } returns Unit

        plugin.sendCameraList()

        verify(exactly = 1) { device.sendPacket(any()) }

        val sentPacket = packetSlot.captured
        assertEquals(CameraProtocol.PACKET_TYPE_CAMERA_LIST, sentPacket.type)

        val camerasArray = sentPacket.getJSONArray(CameraProtocol.KEY_CAMERAS)
        // The key must be present with an empty array, not missing
        assertTrue(camerasArray != null)
        assertEquals(0, camerasArray!!.length())
    }

    @Test
    fun camera2CatalogWithNullConfigMapYieldsBackCameraWithEmptySizes() {
        // CameraCharacteristics.get() is a stub in android.jar that passes null args.
        // Use a relaxed mock with specific answers for the three keys we care about.
        val chars = mockk<CameraCharacteristics>(relaxed = true) {
            // The stub get() returns null by default; we need to return specific values.
            // Since the stub doesn't pass real args, we use every { get(any()) } with
            // a counter-based approach.
        }
        // Override get() to return the right values in the order they're called:
        // 1st call: LENS_FACING -> LENS_FACING_BACK
        // 2nd call: FLASH_INFO_AVAILABLE -> false
        // 3rd call: SCALER_STREAM_CONFIGURATION_MAP -> null
        var callCount = 0
        every { chars.get(any<CameraCharacteristics.Key<Any>>()) } answers {
            callCount++
            when (callCount) {
                1 -> CameraCharacteristics.LENS_FACING_BACK
                2 -> false
                else -> null
            }
        }

        val cameraManager = mockk<CameraManager> {
            every { cameraIdList } returns arrayOf("0")
            every { getCameraCharacteristics("0") } returns chars
        }

        val ctx = mockk<Context>()
        // Inject a fake CameraManager provider to avoid static mocking of ContextCompat
        val catalog = Camera2Catalog(ctx) { cameraManager }
        val cameras = catalog.listCameras()

        assertEquals(1, cameras.size)
        assertEquals("0", cameras[0].id)
        assertEquals(CameraProtocol.FACING_BACK, cameras[0].facing)
        assertFalse(cameras[0].hasFlash)
        assertTrue(cameras[0].sizes.isEmpty())
    }

    // ---- CAM-4 tests ----

    @Test
    fun startWithEmptyCatalogSendsUnsupportedError() {
        plugin.catalogProvider = { UnavailableCameraCatalog() }

        val errorPacketSlot = slot<NetworkPacket>()
        every { device.sendPacket(capture(errorPacketSlot)) } returns Unit

        val np = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        np[CameraProtocol.KEY_CAMERA_ID] = "0"
        val result = plugin.onPacketReceived(np)

        assertTrue(result)
        verify(exactly = 1) { device.sendPacket(any()) }
        val errorPacket = errorPacketSlot.captured
        assertEquals(CameraProtocol.PACKET_TYPE_CAMERA_ERROR, errorPacket.type)
        assertEquals(CameraProtocol.ERROR_UNSUPPORTED, errorPacket.getString(CameraProtocol.KEY_ERROR))
    }

    @Test
    fun startWhileSessionActiveSendsInUseError() {
        val fakeCatalog = object : CameraCatalog {
            override fun listCameras(): List<CameraDescription> {
                return listOf(
                    CameraDescription(
                        id = "0",
                        facing = CameraProtocol.FACING_BACK,
                        hasFlash = true,
                        sizes = listOf(CameraSize(1280, 720, 30))
                    )
                )
            }
        }
        plugin.catalogProvider = { fakeCatalog }

        // Inject a fake session via sessionFactory
        val fakeSession = mockk<CameraSession>(relaxed = true)
        plugin.sessionFactory = { _, _, _ -> fakeSession }

        // First start - should succeed
        val startNp = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        startNp[CameraProtocol.KEY_CAMERA_ID] = "0"

        val packetSlots = mutableListOf<NetworkPacket>()
        every { device.sendPacket(capture(packetSlots)) } returns Unit

        plugin.onPacketReceived(startNp)

        // Wait for the background thread to set the session
        Thread.sleep(200)

        // Second start - should get IN_USE error
        val startNp2 = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        startNp2[CameraProtocol.KEY_CAMERA_ID] = "0"
        plugin.onPacketReceived(startNp2)

        // Find the error packet
        val errorPacket = packetSlots.lastOrNull { it.type == CameraProtocol.PACKET_TYPE_CAMERA_ERROR }
        assertTrue("Expected an error packet", errorPacket != null)
        assertEquals(CameraProtocol.ERROR_IN_USE, errorPacket!!.getString(CameraProtocol.KEY_ERROR))
    }

    @Test
    fun stopWithNoSessionDoesNotCrash() {
        // No session active, stop should not crash or send error
        val packetSlots = mutableListOf<NetworkPacket>()
        every { device.sendPacket(capture(packetSlots)) } returns Unit

        val np = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_STOP)
        val result = plugin.onPacketReceived(np)

        assertTrue(result)
        // No error packet should be sent
        assertTrue(packetSlots.none { it.type == CameraProtocol.PACKET_TYPE_CAMERA_ERROR })
    }

    @Test
    fun sessionFailedCallbackSendsErrorAndClearsSession() {
        val fakeCatalog = object : CameraCatalog {
            override fun listCameras(): List<CameraDescription> {
                return listOf(
                    CameraDescription(
                        id = "0",
                        facing = CameraProtocol.FACING_BACK,
                        hasFlash = true,
                        sizes = listOf(CameraSize(1280, 720, 30))
                    )
                )
            }
        }
        plugin.catalogProvider = { fakeCatalog }

        // Capture the callbacks passed to sessionFactory
        var capturedCallbacks: CameraSession.Callbacks? = null
        val fakeSession = mockk<CameraSession>(relaxed = true)
        plugin.sessionFactory = { _, _, callbacks ->
            capturedCallbacks = callbacks
            fakeSession
        }

        val packetSlots = mutableListOf<NetworkPacket>()
        every { device.sendPacket(capture(packetSlots)) } returns Unit

        val startNp = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        startNp[CameraProtocol.KEY_CAMERA_ID] = "0"
        plugin.onPacketReceived(startNp)

        // Wait for background thread
        Thread.sleep(200)

        // Simulate session failure
        capturedCallbacks?.onSessionFailed(CameraError.IN_USE)

        // Verify error packet sent
        val errorPacket = packetSlots.lastOrNull { it.type == CameraProtocol.PACKET_TYPE_CAMERA_ERROR }
        assertTrue("Expected an error packet", errorPacket != null)
        assertEquals(CameraProtocol.ERROR_IN_USE, errorPacket!!.getString(CameraProtocol.KEY_ERROR))

        // Verify session field is cleared - a new start should not get IN_USE
        packetSlots.clear()
        val startNp2 = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        startNp2[CameraProtocol.KEY_CAMERA_ID] = "0"
        plugin.onPacketReceived(startNp2)

        // Should not have an IN_USE error (session was cleared)
        val inUseError = packetSlots.none { it.type == CameraProtocol.PACKET_TYPE_CAMERA_ERROR && it.getString(CameraProtocol.KEY_ERROR) == CameraProtocol.ERROR_IN_USE }
        assertTrue("Session should have been cleared", inUseError)
    }

    @Test
    fun clampRequestDefaults() {
        val catalog = listOf(
            CameraDescription(
                id = "0",
                facing = CameraProtocol.FACING_BACK,
                hasFlash = true,
                sizes = listOf(CameraSize(1280, 720, 30))
            )
        )
        val np = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        // No fields set - should use defaults
        val req = CameraPlugin.clampRequest(np, catalog)

        assertTrue(req != null)
        assertEquals("0", req!!.cameraId)
        assertEquals(1280, req.width)
        assertEquals(720, req.height)
        assertEquals(30, req.fps)
        assertEquals(4_000_000, req.bitrate)
    }

    @Test
    fun clampRequestClampsBounds() {
        val catalog = listOf(
            CameraDescription(
                id = "0",
                facing = CameraProtocol.FACING_BACK,
                hasFlash = true,
                sizes = listOf(CameraSize(1280, 720, 30))
            )
        )

        // Test lower bounds
        val np1 = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        np1[CameraProtocol.KEY_CAMERA_ID] = "0"
        np1[CameraProtocol.KEY_FPS] = 0
        np1[CameraProtocol.KEY_BITRATE] = 10
        val req1 = CameraPlugin.clampRequest(np1, catalog)
        assertTrue(req1 != null)
        assertEquals(1, req1!!.fps) // 0 -> 1
        assertEquals(100_000, req1.bitrate) // 10 -> 100_000

        // Test upper bounds
        val np2 = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        np2[CameraProtocol.KEY_CAMERA_ID] = "0"
        np2[CameraProtocol.KEY_FPS] = 240
        np2[CameraProtocol.KEY_BITRATE] = 1_000_000_000
        val req2 = CameraPlugin.clampRequest(np2, catalog)
        assertTrue(req2 != null)
        assertEquals(60, req2!!.fps) // 240 -> 60
        assertEquals(16_000_000, req2.bitrate) // 1e9 -> 16e6
    }

    @Test
    fun clampRequestNullWhenCatalogEmpty() {
        val np = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        np[CameraProtocol.KEY_CAMERA_ID] = "0"
        val req = CameraPlugin.clampRequest(np, emptyList())
        assertTrue(req == null)
    }

    @Test
    fun clampRequestFallbackToBackCamera() {
        val catalog = listOf(
            CameraDescription(
                id = "1",
                facing = CameraProtocol.FACING_FRONT,
                hasFlash = false,
                sizes = listOf(CameraSize(1280, 720, 30))
            ),
            CameraDescription(
                id = "0",
                facing = CameraProtocol.FACING_BACK,
                hasFlash = true,
                sizes = listOf(CameraSize(1280, 720, 30))
            )
        )
        val np = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        // No cameraId set - should fall back to back camera
        val req = CameraPlugin.clampRequest(np, catalog)

        assertTrue(req != null)
        assertEquals("0", req!!.cameraId) // back camera
    }

    @Test
    fun clampRequestFallbackToFirstCameraWhenNoBack() {
        val catalog = listOf(
            CameraDescription(
                id = "1",
                facing = CameraProtocol.FACING_FRONT,
                hasFlash = false,
                sizes = listOf(CameraSize(1280, 720, 30))
            ),
            CameraDescription(
                id = "2",
                facing = CameraProtocol.FACING_EXTERNAL,
                hasFlash = false,
                sizes = listOf(CameraSize(640, 480, 30))
            )
        )
        val np = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        // No cameraId set - should fall back to first camera
        val req = CameraPlugin.clampRequest(np, catalog)

        assertTrue(req != null)
        assertEquals("1", req!!.cameraId) // first camera
    }

    @Test
    fun onPacketReceivedCameraStartReturnsTrue() {
        plugin.catalogProvider = { UnavailableCameraCatalog() }

        val np = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        val result = plugin.onPacketReceived(np)

        assertTrue(result)
    }

    // ---- CAM-5 tests ----

    @Test
    fun stopSessionUserInitiatedSendsStoppedError() {
        val fakeCatalog = object : CameraCatalog {
            override fun listCameras(): List<CameraDescription> {
                return listOf(
                    CameraDescription(
                        id = "0",
                        facing = CameraProtocol.FACING_BACK,
                        hasFlash = true,
                        sizes = listOf(CameraSize(1280, 720, 30))
                    )
                )
            }
        }
        plugin.catalogProvider = { fakeCatalog }

        val fakeSession = mockk<CameraSession>(relaxed = true)
        plugin.sessionFactory = { _, _, _ -> fakeSession }

        // Replace sendCameraServiceIntent seam to no-op
        plugin.sendCameraServiceIntent = { }

        val packetSlots = mutableListOf<NetworkPacket>()
        every { device.sendPacket(capture(packetSlots)) } returns Unit

        // Start a session
        val startNp = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        startNp[CameraProtocol.KEY_CAMERA_ID] = "0"
        plugin.onPacketReceived(startNp)
        Thread.sleep(200)

        // Stop with userInitiated=true
        plugin.stopSession(userInitiated = true)

        // Verify stop() was called on the session
        verify(exactly = 1) { fakeSession.stop() }

        // Verify STOPPED error packet was sent
        val errorPacket = packetSlots.lastOrNull { it.type == CameraProtocol.PACKET_TYPE_CAMERA_ERROR }
        assertTrue("Expected a STOPPED error packet", errorPacket != null)
        assertEquals(CameraProtocol.ERROR_STOPPED, errorPacket!!.getString(CameraProtocol.KEY_ERROR))
    }

    @Test
    fun stopSessionNotUserInitiatedSendsNoError() {
        val fakeCatalog = object : CameraCatalog {
            override fun listCameras(): List<CameraDescription> {
                return listOf(
                    CameraDescription(
                        id = "0",
                        facing = CameraProtocol.FACING_BACK,
                        hasFlash = true,
                        sizes = listOf(CameraSize(1280, 720, 30))
                    )
                )
            }
        }
        plugin.catalogProvider = { fakeCatalog }

        val fakeSession = mockk<CameraSession>(relaxed = true)
        plugin.sessionFactory = { _, _, _ -> fakeSession }

        // Replace sendCameraServiceIntent seam to no-op
        plugin.sendCameraServiceIntent = { }

        val packetSlots = mutableListOf<NetworkPacket>()
        every { device.sendPacket(capture(packetSlots)) } returns Unit

        // Start a session
        val startNp = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        startNp[CameraProtocol.KEY_CAMERA_ID] = "0"
        plugin.onPacketReceived(startNp)
        Thread.sleep(200)

        // Stop with userInitiated=false
        plugin.stopSession(userInitiated = false)

        // Verify stop() was called
        verify(exactly = 1) { fakeSession.stop() }

        // Verify NO error packet was sent
        assertTrue(packetSlots.none { it.type == CameraProtocol.PACKET_TYPE_CAMERA_ERROR })
    }

    @Test
    fun stopSessionWithNoSessionDoesNotCrash() {
        // Replace sendCameraServiceIntent seam to no-op
        plugin.sendCameraServiceIntent = { }

        val packetSlots = mutableListOf<NetworkPacket>()
        every { device.sendPacket(capture(packetSlots)) } returns Unit

        // stopSession with no active session should not crash
        plugin.stopSession(userInitiated = true)

        // Only the STOPPED error packet should be sent (no session to stop)
        val errorPacket = packetSlots.lastOrNull { it.type == CameraProtocol.PACKET_TYPE_CAMERA_ERROR }
        assertTrue("Expected a STOPPED error packet", errorPacket != null)
        assertEquals(CameraProtocol.ERROR_STOPPED, errorPacket!!.getString(CameraProtocol.KEY_ERROR))
    }

    @Test
    fun sendCameraServiceIntentPromoteOnStart() {
        val fakeCatalog = object : CameraCatalog {
            override fun listCameras(): List<CameraDescription> {
                return listOf(
                    CameraDescription(
                        id = "0",
                        facing = CameraProtocol.FACING_BACK,
                        hasFlash = true,
                        sizes = listOf(CameraSize(1280, 720, 30))
                    )
                )
            }
        }
        plugin.catalogProvider = { fakeCatalog }

        val fakeSession = mockk<CameraSession>(relaxed = true)
        plugin.sessionFactory = { _, _, _ -> fakeSession }

        // Record sendCameraServiceIntent calls
        val serviceCalls = mutableListOf<Boolean>()
        plugin.sendCameraServiceIntent = { active -> serviceCalls.add(active) }

        every { device.sendPacket(any()) } returns Unit

        // Start a session
        val startNp = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        startNp[CameraProtocol.KEY_CAMERA_ID] = "0"
        plugin.onPacketReceived(startNp)
        Thread.sleep(200)

        // Verify promote (active=true) was sent
        assertTrue("Expected promote intent", serviceCalls.contains(true))
    }

    @Test
    fun sendCameraServiceIntentDemoteOnStop() {
        val fakeCatalog = object : CameraCatalog {
            override fun listCameras(): List<CameraDescription> {
                return listOf(
                    CameraDescription(
                        id = "0",
                        facing = CameraProtocol.FACING_BACK,
                        hasFlash = true,
                        sizes = listOf(CameraSize(1280, 720, 30))
                    )
                )
            }
        }
        plugin.catalogProvider = { fakeCatalog }

        val fakeSession = mockk<CameraSession>(relaxed = true)
        plugin.sessionFactory = { _, _, _ -> fakeSession }

        // Record sendCameraServiceIntent calls
        val serviceCalls = mutableListOf<Boolean>()
        plugin.sendCameraServiceIntent = { active -> serviceCalls.add(active) }

        every { device.sendPacket(any()) } returns Unit

        // Start a session
        val startNp = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        startNp[CameraProtocol.KEY_CAMERA_ID] = "0"
        plugin.onPacketReceived(startNp)
        Thread.sleep(200)

        // Stop the session
        plugin.stopSession(userInitiated = false)

        // Verify demote (active=false) was sent
        assertTrue("Expected demote intent", serviceCalls.contains(false))
    }

    @Test
    fun sendCameraServiceIntentDemoteOnFailure() {
        val fakeCatalog = object : CameraCatalog {
            override fun listCameras(): List<CameraDescription> {
                return listOf(
                    CameraDescription(
                        id = "0",
                        facing = CameraProtocol.FACING_BACK,
                        hasFlash = true,
                        sizes = listOf(CameraSize(1280, 720, 30))
                    )
                )
            }
        }
        plugin.catalogProvider = { fakeCatalog }

        var capturedCallbacks: CameraSession.Callbacks? = null
        val fakeSession = mockk<CameraSession>(relaxed = true)
        plugin.sessionFactory = { _, _, callbacks ->
            capturedCallbacks = callbacks
            fakeSession
        }

        // Record sendCameraServiceIntent calls
        val serviceCalls = mutableListOf<Boolean>()
        plugin.sendCameraServiceIntent = { active -> serviceCalls.add(active) }

        every { device.sendPacket(any()) } returns Unit

        // Start a session
        val startNp = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        startNp[CameraProtocol.KEY_CAMERA_ID] = "0"
        plugin.onPacketReceived(startNp)
        Thread.sleep(200)

        // Simulate session failure
        capturedCallbacks?.onSessionFailed(CameraError.DISCONNECTED)

        // Verify demote (active=false) was sent
        assertTrue("Expected demote intent on failure", serviceCalls.contains(false))
    }

    @Test
    fun wakeLockAcquiredOnStartAndReleasedOnStop() {
        val fakeCatalog = object : CameraCatalog {
            override fun listCameras(): List<CameraDescription> {
                return listOf(
                    CameraDescription(
                        id = "0",
                        facing = CameraProtocol.FACING_BACK,
                        hasFlash = true,
                        sizes = listOf(CameraSize(1280, 720, 30))
                    )
                )
            }
        }
        plugin.catalogProvider = { fakeCatalog }
        val fakeSession = mockk<CameraSession>(relaxed = true)
        plugin.sessionFactory = { _, _, _ -> fakeSession }
        plugin.sendCameraServiceIntent = { }

        // Record wake lock transitions (true = acquire, false = release)
        val wakeCalls = mutableListOf<Boolean>()
        plugin.wakeLockController = { active -> wakeCalls.add(active) }

        every { device.sendPacket(any()) } returns Unit

        // Start a session -> wake lock acquired
        val startNp = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        startNp[CameraProtocol.KEY_CAMERA_ID] = "0"
        plugin.onPacketReceived(startNp)
        Thread.sleep(200)
        assertTrue("Expected wake lock acquire on start", wakeCalls.contains(true))

        // Stop the session -> wake lock released
        plugin.stopSession(userInitiated = false)
        assertTrue("Expected wake lock release on stop", wakeCalls.contains(false))
    }

    @Test
    fun wakeLockReleasedOnSessionFailure() {
        val fakeCatalog = object : CameraCatalog {
            override fun listCameras(): List<CameraDescription> {
                return listOf(
                    CameraDescription(
                        id = "0",
                        facing = CameraProtocol.FACING_BACK,
                        hasFlash = true,
                        sizes = listOf(CameraSize(1280, 720, 30))
                    )
                )
            }
        }
        plugin.catalogProvider = { fakeCatalog }

        var capturedCallbacks: CameraSession.Callbacks? = null
        val fakeSession = mockk<CameraSession>(relaxed = true)
        plugin.sessionFactory = { _, _, callbacks ->
            capturedCallbacks = callbacks
            fakeSession
        }
        plugin.sendCameraServiceIntent = { }

        val wakeCalls = mutableListOf<Boolean>()
        plugin.wakeLockController = { active -> wakeCalls.add(active) }

        every { device.sendPacket(any()) } returns Unit

        val startNp = NetworkPacket(CameraProtocol.PACKET_TYPE_CAMERA_START)
        startNp[CameraProtocol.KEY_CAMERA_ID] = "0"
        plugin.onPacketReceived(startNp)
        Thread.sleep(200)

        // Simulate a session failure -> must release the wake lock
        capturedCallbacks?.onSessionFailed(CameraError.DISCONNECTED)
        assertTrue("Expected wake lock release on failure", wakeCalls.contains(false))
    }

    // ---- CAM-10: startSharing / stopSharing / isSharing / listeners ----

    @Test
    fun startSharingReturnsFalseWhenPermissionMissing() {
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_DENIED

        val request = CameraSession.Request(
            cameraId = "0", width = 1280, height = 720, fps = 30,
            bitrate = 4_000_000, rotationDegrees = 0,
        )

        val result = plugin.startSharing(request)
        assertFalse("startSharing should return false when permission denied", result)
        assertFalse("isSharing should be false", plugin.isSharing())
    }

    @Test
    fun startSharingReturnsFalseWhenSessionBusy() {
        val fakeCatalog = object : CameraCatalog {
            override fun listCameras(): List<CameraDescription> {
                return listOf(
                    CameraDescription(
                        id = "0",
                        facing = CameraProtocol.FACING_BACK,
                        hasFlash = true,
                        sizes = listOf(CameraSize(1280, 720, 30))
                    )
                )
            }
        }
        plugin.catalogProvider = { fakeCatalog }
        plugin.sendCameraServiceIntent = { }

        // Create a fake session that we control
        val fakeSession = mockk<CameraSession>(relaxed = true)
        plugin.sessionFactory = { _, _, _ -> fakeSession }

        every { device.sendPacket(any()) } returns Unit

        // Start first session via startSharing
        val request1 = CameraSession.Request(
            cameraId = "0", width = 1280, height = 720, fps = 30,
            bitrate = 4_000_000, rotationDegrees = 0,
        )
        val result1 = plugin.startSharing(request1)
        assertTrue("First startSharing should succeed", result1)

        // Wait for background thread
        Thread.sleep(200)

        // Second startSharing should return false
        val request2 = CameraSession.Request(
            cameraId = "0", width = 640, height = 480, fps = 15,
            bitrate = 2_000_000, rotationDegrees = 90,
        )
        val result2 = plugin.startSharing(request2)
        assertFalse("startSharing should return false when busy", result2)
    }

    @Test
    fun startSharingHappyPathInvokesSessionFactoryWithRotation() {
        val fakeCatalog = object : CameraCatalog {
            override fun listCameras(): List<CameraDescription> {
                return listOf(
                    CameraDescription(
                        id = "0",
                        facing = CameraProtocol.FACING_BACK,
                        hasFlash = true,
                        sizes = listOf(CameraSize(1920, 1080, 30))
                    )
                )
            }
        }
        plugin.catalogProvider = { fakeCatalog }
        plugin.sendCameraServiceIntent = { }

        var capturedRequest: CameraSession.Request? = null
        val fakeSession = mockk<CameraSession>(relaxed = true)
        plugin.sessionFactory = { _, req, _ ->
            capturedRequest = req
            fakeSession
        }

        every { device.sendPacket(any()) } returns Unit

        // Capture active listener notifications
        var lastListenerValue: Boolean? = null
        val listener: (Boolean) -> Unit = { active -> lastListenerValue = active }
        plugin.addActiveListener(listener)

        val request = CameraSession.Request(
            cameraId = "0", width = 1920, height = 1080, fps = 30,
            bitrate = 4_000_000, rotationDegrees = 90,
        )
        val result = plugin.startSharing(request)
        assertTrue("startSharing should succeed", result)

        // Wait for background thread
        Thread.sleep(300)

        // Verify the exact request was forwarded
        val req = capturedRequest
        assertTrue("Request should have been captured", req != null)
        assertEquals("0", req!!.cameraId)
        assertEquals(1920, req.width)
        assertEquals(1080, req.height)
        assertEquals(30, req.fps)
        assertEquals(4_000_000, req.bitrate)
        assertEquals(90, req.rotationDegrees)

        // Verify isSharing
        assertTrue("isSharing should be true", plugin.isSharing())

        // Verify listener was notified (on main thread — wait a bit)
        Thread.sleep(100)
        assertEquals("Listener should have been notified with true", true, lastListenerValue)

        plugin.removeActiveListener(listener)
    }

    @Test
    fun stopSharingStopsSessionAndNotifiesListener() {
        val fakeCatalog = object : CameraCatalog {
            override fun listCameras(): List<CameraDescription> {
                return listOf(
                    CameraDescription(
                        id = "0",
                        facing = CameraProtocol.FACING_BACK,
                        hasFlash = true,
                        sizes = listOf(CameraSize(1280, 720, 30))
                    )
                )
            }
        }
        plugin.catalogProvider = { fakeCatalog }
        plugin.sendCameraServiceIntent = { }

        val fakeSession = mockk<CameraSession>(relaxed = true)
        plugin.sessionFactory = { _, _, _ -> fakeSession }

        val packetSlots = mutableListOf<NetworkPacket>()
        every { device.sendPacket(capture(packetSlots)) } returns Unit

        var lastListenerValue: Boolean? = null
        val listener: (Boolean) -> Unit = { active -> lastListenerValue = active }
        plugin.addActiveListener(listener)

        // Start via startSharing
        val request = CameraSession.Request(
            cameraId = "0", width = 1280, height = 720, fps = 30,
            bitrate = 4_000_000, rotationDegrees = 0,
        )
        plugin.startSharing(request)
        Thread.sleep(200)

        assertTrue("isSharing should be true before stop", plugin.isSharing())

        // Stop
        plugin.stopSharing()

        // Verify session stopped
        verify(exactly = 1) { fakeSession.stop() }
        assertFalse("isSharing should be false after stop", plugin.isSharing())

        // Verify STOPPED error packet sent (userInitiated = true)
        val errorPacket = packetSlots.lastOrNull { it.type == CameraProtocol.PACKET_TYPE_CAMERA_ERROR }
        assertTrue("Expected STOPPED error packet", errorPacket != null)
        assertEquals(CameraProtocol.ERROR_STOPPED, errorPacket!!.getString(CameraProtocol.KEY_ERROR))

        // Verify listener notified false
        Thread.sleep(100)
        assertEquals("Listener should have been notified with false", false, lastListenerValue)

        plugin.removeActiveListener(listener)
    }


    // ---- CAM-11: stale session callbacks must not tear down the live session ----

    private fun singleCameraCatalog(): CameraCatalog = object : CameraCatalog {
        override fun listCameras(): List<CameraDescription> = listOf(
            CameraDescription(
                id = "0",
                facing = CameraProtocol.FACING_BACK,
                hasFlash = true,
                sizes = listOf(CameraSize(1280, 720, 30))
            )
        )
    }

    private fun sampleRequest() = CameraSession.Request(
        cameraId = "0", width = 1280, height = 720, fps = 30,
        bitrate = 4_000_000, rotationDegrees = 0,
    )

    /**
     * A session's callbacks are delivered asynchronously from its own camera
     * handler thread, so `onSessionStopped` for a superseded session can arrive
     * after a newer session is already live.
     *
     * Regression test: the stale callback used to clear `session` and release
     * the wake lock / foreground service unconditionally, tearing down the
     * replacement session while its camera was still streaming.
     */
    @Test
    fun staleSessionStoppedCallbackDoesNotTearDownLiveSession() {
        plugin.catalogProvider = { singleCameraCatalog() }

        // Sessions in creation order, each with the callbacks it was built with.
        val sessions = Collections.synchronizedList(mutableListOf<CameraSession>())
        val callbacks = Collections.synchronizedList(mutableListOf<CameraSession.Callbacks>())
        plugin.sessionFactory = { _, _, cb ->
            mockk<CameraSession>(relaxed = true).also {
                sessions.add(it)
                callbacks.add(cb)
            }
        }

        val wakeCalls = Collections.synchronizedList(mutableListOf<Boolean>())
        plugin.wakeLockController = { active -> wakeCalls.add(active) }

        val serviceCalls = Collections.synchronizedList(mutableListOf<Boolean>())
        plugin.sendCameraServiceIntent = { active -> serviceCalls.add(active) }

        val listenerValues = Collections.synchronizedList(mutableListOf<Boolean>())
        val listener: (Boolean) -> Unit = { active -> listenerValues.add(active) }
        plugin.addActiveListener(listener)

        every { device.sendPacket(any()) } returns Unit

        // Session #1 goes live.
        plugin.startSharing(sampleRequest())
        Thread.sleep(200)
        assertTrue("session #1 should be live", plugin.isSharing())

        // Stop it and start a replacement before #1's async stop callback lands.
        plugin.stopSession(userInitiated = false)
        assertFalse("session should be detached by stopSession", plugin.isSharing())

        plugin.startSharing(sampleRequest())
        Thread.sleep(200)
        assertTrue("session #2 should be live", plugin.isSharing())
        assertEquals("two sessions should have been created", 2, sessions.size)

        // The wake lock and foreground service belong to session #2 now.
        assertEquals("wake lock should be held for session #2", true, wakeCalls.last())
        assertEquals("service should be promoted for session #2", true, serviceCalls.last())
        assertEquals("listeners should see session #2 as active", true, listenerValues.last())

        // Now the stale callback from session #1 finally arrives.
        callbacks[0].onSessionStopped()

        assertTrue("stale onSessionStopped must not detach the live session", plugin.isSharing())
        assertEquals(
            "stale callback must not release the wake lock of the live session",
            true, wakeCalls.last()
        )
        assertEquals(
            "stale callback must not demote the foreground service of the live session",
            true, serviceCalls.last()
        )
        assertEquals(
            "stale callback must not notify listeners that the live session ended",
            true, listenerValues.last()
        )

        plugin.removeActiveListener(listener)
    }

    /**
     * Same guard, failure path: a late `onSessionFailed` from a superseded
     * session must neither detach the live session nor report a bogus error to
     * the desktop, which would make the UI show a working camera as broken.
     */
    @Test
    fun staleSessionFailedCallbackDoesNotTearDownLiveSession() {
        plugin.catalogProvider = { singleCameraCatalog() }

        val callbacks = Collections.synchronizedList(mutableListOf<CameraSession.Callbacks>())
        plugin.sessionFactory = { _, _, cb ->
            mockk<CameraSession>(relaxed = true).also { callbacks.add(cb) }
        }
        plugin.sendCameraServiceIntent = { }
        plugin.wakeLockController = { }

        val packetSlots = Collections.synchronizedList(mutableListOf<NetworkPacket>())
        every { device.sendPacket(capture(packetSlots)) } returns Unit

        plugin.startSharing(sampleRequest())
        Thread.sleep(200)
        assertEquals("one session should have been created", 1, callbacks.size)

        plugin.stopSession(userInitiated = false)
        plugin.startSharing(sampleRequest())
        Thread.sleep(200)
        assertTrue("session #2 should be live", plugin.isSharing())

        // Drop everything sent so far, then let the stale failure land.
        packetSlots.clear()
        callbacks[0].onSessionFailed(CameraError.DISCONNECTED)

        assertTrue("stale onSessionFailed must not detach the live session", plugin.isSharing())
        assertTrue(
            "stale onSessionFailed must not send an error packet for the live session",
            packetSlots.none { it.type == CameraProtocol.PACKET_TYPE_CAMERA_ERROR }
        )
    }

    /**
     * Concurrent START requests must never leave two sessions installed: the
     * losers are stopped, and their late callbacks must not disturb the winner.
     */
    @Test
    fun concurrentStartsInstallExactlyOneSession() {
        plugin.catalogProvider = { singleCameraCatalog() }

        val stopped = Collections.synchronizedSet(mutableSetOf<CameraSession>())
        val entries = Collections.synchronizedList(
            mutableListOf<Pair<CameraSession, CameraSession.Callbacks>>()
        )
        plugin.sessionFactory = { _, _, cb ->
            val s = mockk<CameraSession>(relaxed = true) {
                every { stop() } answers { stopped.add(this@mockk) }
            }
            entries.add(s to cb)
            s
        }
        plugin.sendCameraServiceIntent = { }
        plugin.wakeLockController = { }
        every { device.sendPacket(any()) } returns Unit

        val threads = (1..8).map { Thread { plugin.startSharing(sampleRequest()) } }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }
        Thread.sleep(500)

        assertTrue("exactly one session should remain installed", plugin.isSharing())
        assertTrue("at least one session should have been created", entries.isNotEmpty())

        // Every session that lost the race must have been stopped; the winner
        // must not have been.
        val winners = entries.map { it.first }.filter { it !in stopped }
        assertEquals("exactly one session should have survived", 1, winners.size)

        // Delivering every loser's late callback must not disturb the winner.
        entries.forEach { (session, cb) -> if (session in stopped) cb.onSessionStopped() }
        assertTrue("late loser callbacks must not detach the winning session", plugin.isSharing())
    }
}
