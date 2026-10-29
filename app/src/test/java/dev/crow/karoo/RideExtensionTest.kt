package dev.crow.karoo

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class RideExtensionTest {
    private fun field(extension: CrowExtension, id: String, preview: Boolean): View {
        val app = RuntimeEnvironment.getApplication()
        val updates = mutableListOf<Bundle>()
        val handler = object : IHandler.Stub() {
            override fun onNext(bundle: Bundle) { updates.add(bundle) }
            override fun onError(message: String) { fail(message) }
            override fun onComplete() = Unit
        }
        val emitter = ViewEmitter(app.packageName, handler)
        extension.types.single { it.typeId == id }.startView(app, ViewConfig(30 to 15, 240 to 160, 20, preview = preview), emitter)
        ShadowLooper.idleMainLooper()
        @Suppress("DEPRECATION")
        val remote = updates.mapNotNull { it.getParcelable<RemoteViews>("view") }.single()
        val view = remote.apply(app, FrameLayout(app))
        emitter.cancel()
        ShadowLooper.idleMainLooper()
        return view
    }

    @Test fun allFiveFieldsAreRegisteredAndSeparatePreviewsHaveNoClickActions() {
        val app = RuntimeEnvironment.getApplication()
        val extension = Robolectric.buildService(CrowExtension::class.java).get()
        val declared = mutableSetOf<String>()
        app.resources.getXml(R.xml.extension_info).use { xml ->
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "DataType") declared.add(xml.getAttributeValue(null, "typeId"))
                xml.next()
            }
        }
        assertEquals(extension.types.map { it.typeId }.toSet(), declared)
        assertEquals(5, declared.size)
        val values = mapOf("camera-record" to "Start", "camera-photo" to "Take photo", "camera-status" to "Ready", "camera-mode" to "Video")
        for ((id, value) in values) {
            val view = field(extension, id, true)
            assertEquals(value, view.findViewById<TextView>(R.id.field_value).text.toString())
            assertFalse(view.findViewById<View>(R.id.field_root).isEnabled)
            assertFalse(view.findViewById<View>(R.id.field_root).hasOnClickListeners())
        }
        assertFalse(CameraSession.get(app).inUse())
    }

    @Test fun separateFieldsShowRecordingAndDisablePhotoModeAndStaleControls() {
        val app = RuntimeEnvironment.getApplication()
        val extension = Robolectric.buildService(CrowExtension::class.java).get()
        val session = CameraSession.get(app)
        val link = session.link
        link.ready = true; link.mode = 1; link.state = 3; link.battery = 85; link.seconds = 462
        link.statusAt = SystemClock.elapsedRealtime()
        try {
            val record = field(extension, "camera-record", false)
            assertEquals("Stop", record.findViewById<TextView>(R.id.field_value).text.toString())
            assertTrue(record.findViewById<View>(R.id.field_root).isEnabled)
            assertFalse(field(extension, "camera-photo", false).findViewById<View>(R.id.field_root).isEnabled)
            assertFalse(field(extension, "camera-mode", false).findViewById<View>(R.id.field_root).isEnabled)
            val status = field(extension, "camera-status", false)
            assertEquals("Recording", status.findViewById<TextView>(R.id.field_value).text.toString())
            assertEquals("85% • 07:42", status.findViewById<TextView>(R.id.field_detail).text.toString())
            assertFalse(status.findViewById<View>(R.id.field_root).hasOnClickListeners())
            link.statusAt = SystemClock.elapsedRealtime() - 6000
            assertFalse(field(extension, "camera-record", false).findViewById<View>(R.id.field_root).isEnabled)
            assertEquals("No signal", field(extension, "camera-status", false).findViewById<TextView>(R.id.field_value).text.toString())
        } finally { session.release(extension) }
    }

    private fun owner() = object : CameraLink.Listener {
        override fun changed() = Unit
        override fun found(device: BluetoothDevice, name: String) = Unit
    }

    @Test fun leavingAppPreservesExtensionSessionAndLastOwnerClosesIt() {
        val app = RuntimeEnvironment.getApplication()
        val session = CameraSession.get(app)
        val activity = owner()
        val extension = owner()
        session.acquire(activity)
        session.acquire(extension)
        session.acquire(extension) // Repeated view subscriptions must not leak an extra owner.
        assertSame(session.link, CameraSession.get(app).link)
        session.link.ready = true
        session.link.mode = 5
        session.release(activity)
        assertTrue(session.inUse())
        assertTrue(session.link.ready)
        assertEquals(5, session.link.mode)
        session.release(extension)
        assertFalse(session.inUse())
        assertFalse(session.link.ready)
        assertEquals(-1, session.link.mode)
    }

    @Test fun profilePreviewInflatesWithoutConnectingAndCannotSendCommands() {
        val app = RuntimeEnvironment.getApplication()
        val extension = Robolectric.buildService(CrowExtension::class.java).get()
        val updates = mutableListOf<Bundle>()
        val handler = object : IHandler.Stub() {
            override fun onNext(bundle: Bundle) { updates.add(bundle) }
            override fun onError(message: String) { fail(message) }
            override fun onComplete() = Unit
        }
        val emitter = ViewEmitter(app.packageName, handler)
        val config = ViewConfig(60 to 30, 480 to 400, 20, preview = true)
        extension.types.single { it.typeId == "camera" }.startView(app, config, emitter)
        ShadowLooper.idleMainLooper()
        @Suppress("DEPRECATION")
        val remote = updates.mapNotNull { it.getParcelable<RemoteViews>("view") }.single()
        val view = remote.apply(app, FrameLayout(app))
        assertFalse(view.findViewById<Button>(R.id.camera_shutter).isEnabled)
        assertFalse(view.findViewById<Button>(R.id.camera_photo).isEnabled)
        assertFalse(view.findViewById<Button>(R.id.camera_video).isEnabled)
        assertFalse(CameraSession.get(app).inUse())
        emitter.cancel()
        ShadowLooper.idleMainLooper()
        val count = updates.size
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(count, updates.size)
    }
}
