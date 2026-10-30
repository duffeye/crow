package dev.crow.karoo

import android.bluetooth.BluetoothDevice
import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import android.content.BroadcastReceiver
import android.content.Intent
import java.util.concurrent.TimeUnit
import org.xmlpull.v1.XmlPullParser
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], instrumentedPackages = ["io.hammerhead.karooext.internal"])
@LooperMode(LooperMode.Mode.PAUSED)
class RideExtensionTest {
    @Before fun initializeEmitterClock() {
        // SDK throttling uses wall time. Start beyond its initial 0 timestamp in the simulated clock.
        ShadowSystemClock.advanceBy(1, TimeUnit.SECONDS)
    }
    private fun field(extension: CrowExtension, id: String, preview: Boolean, config: ViewConfig = ViewConfig(30 to 15, 240 to 160, 20, preview = preview)): View {
        val app = RuntimeEnvironment.getApplication()
        val updates = mutableListOf<Bundle>()
        val handler = object : IHandler.Stub() {
            override fun onNext(bundle: Bundle) { updates.add(bundle) }
            override fun onError(message: String) { fail(message) }
            override fun onComplete() = Unit
        }
        val emitter = ViewEmitter(app.packageName, handler)
        extension.types.single { it.typeId == id }.startView(app, config, emitter)
        ShadowLooper.idleMainLooper()
        val header = updates.mapNotNull { it.getString("value") }.map { JSONObject(it) }.single { it.has("showHeader") }
        assertEquals(id !in setOf("camera-record", "camera-photo", "camera-mode"), header.getBoolean("showHeader"))
        @Suppress("DEPRECATION")
        val remote = updates.mapNotNull { it.getParcelable<RemoteViews>("view") }.single()
        val view = remote.apply(app, FrameLayout(app))
        emitter.cancel()
        ShadowLooper.idleMainLooper()
        return view
    }

    @Test fun allEightFieldsAreRegisteredAndSeparatePreviewsHaveNoClickActions() {
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
        assertEquals(8, declared.size)
        val values = mapOf("camera-record" to "Start", "camera-photo" to "Shoot", "camera-status" to "Ready", "camera-mode" to "Video",
            "camera-battery" to "85%", "camera-video-left" to "1h 24m", "camera-photos-left" to "1,234")
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
            assertEquals("REC", status.findViewById<TextView>(R.id.field_value).text.toString())
            assertEquals("85% • 07:42", status.findViewById<TextView>(R.id.field_detail).text.toString())
            assertFalse(status.findViewById<View>(R.id.field_root).hasOnClickListeners())
            link.statusAt = SystemClock.elapsedRealtime() - 6000
            assertFalse(field(extension, "camera-record", false).findViewById<View>(R.id.field_root).isEnabled)
            assertEquals("No signal", field(extension, "camera-status", false).findViewById<TextView>(R.id.field_value).text.toString())
        } finally { session.release(extension) }
    }

    @Test
    @Config(qualifiers = "mdpi")
    fun compactFieldsFitBelowNativeHeaderEvenWithLargeHostTextSize() {
        val app = RuntimeEnvironment.getApplication()
        val extension = Robolectric.buildService(CrowExtension::class.java).get()
        for (height in listOf(80, 96, 120, 160)) {
            for (id in listOf("camera-record", "camera-photo", "camera-status", "camera-mode", "camera-battery", "camera-video-left", "camera-photos-left")) {
                val view = field(extension, id, true, ViewConfig(30 to 10, 160 to height, 72, preview = true))
                val viewport = if (id in setOf("camera-record", "camera-photo", "camera-mode")) height else height - 32
                view.measure(View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(viewport, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                val body = view.findViewById<View>(R.id.field_body)
                val value = view.findViewById<TextView>(R.id.field_value)
                assertTrue("$id at ${height}px: content exceeds available body", (value.parent as View).height <= body.height)
                assertEquals(0, value.layout.getEllipsisCount(0))
                if (height <= 96) assertEquals(View.GONE, view.findViewById<View>(R.id.field_detail).visibility)
            }
        }
    }

    @Test fun capacityTilesRemainReadOnlyAndDoNotDisplayStaleOrInventedValues() {
        val app = RuntimeEnvironment.getApplication()
        val extension = Robolectric.buildService(CrowExtension::class.java).get()
        val session = CameraSession.get(app)
        val link = session.link
        link.ready = true; link.mode = 1; link.state = 1; link.battery = 7
        link.videoSecondsRemaining = 5070; link.photosRemaining = 1234
        link.statusAt = SystemClock.elapsedRealtime()
        try {
            for ((id, expected) in mapOf("camera-battery" to "7%", "camera-video-left" to "1h 24m", "camera-photos-left" to "1,234")) {
                val view = field(extension, id, false)
                assertEquals(expected, view.findViewById<TextView>(R.id.field_value).text.toString())
                assertFalse(view.hasOnClickListeners())
                assertEquals(View.GONE, view.findViewById<View>(R.id.field_header).visibility)
            }
            link.busy = true
            assertEquals("1h 24m", field(extension, "camera-video-left", false).findViewById<TextView>(R.id.field_value).text.toString())
            link.videoSecondsRemaining = 0; link.photosRemaining = 0
            assertEquals("0h 00m", field(extension, "camera-video-left", false).findViewById<TextView>(R.id.field_value).text.toString())
            assertEquals("0", field(extension, "camera-photos-left", false).findViewById<TextView>(R.id.field_value).text.toString())
            link.photosRemaining = -1
            assertEquals("—", field(extension, "camera-photos-left", false).findViewById<TextView>(R.id.field_value).text.toString())
            link.statusAt = SystemClock.elapsedRealtime() - 6000
            for (id in listOf("camera-battery", "camera-video-left", "camera-photos-left"))
                assertEquals("Offline", field(extension, id, false).findViewById<TextView>(R.id.field_value).text.toString())
        } finally { session.release(extension) }
        assertEquals(-1L, link.videoSecondsRemaining)
        assertEquals(-1L, link.photosRemaining)
    }

    @Test
    @Config(qualifiers = "mdpi")
    fun actionTileReceivesOneClickAtHeaderWhitespaceAndEveryCorner() {
        val app = RuntimeEnvironment.getApplication()
        val extension = Robolectric.buildService(CrowExtension::class.java).get()
        val session = CameraSession.get(app)
        val activityController = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            for ((id, action) in mapOf("camera-record" to "record", "camera-photo" to "take-photo", "camera-mode" to "toggle-mode")) {
                session.link.close()
                session.link.ready = true; session.link.mode = 1; session.link.state = 1
                session.link.statusAt = SystemClock.elapsedRealtime()
                val view = field(extension, id, false, ViewConfig(30 to 15, 160 to 120, 72))
                activityController.get().setContentView(view, android.view.ViewGroup.LayoutParams(160, 120))
                view.measure(View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 160, 120)
                assertTrue(view.isEnabled)
                assertEquals(View.VISIBLE, view.findViewById<View>(R.id.field_header).visibility)
                val before = shadowOf(app).broadcastIntents.count { it.action == action }
                val points = listOf(1f to 1f, 159f to 1f, 1f to 119f, 159f to 119f, 80f to 14f, 80f to 110f)
                for ((x, y) in points) {
                    val now = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                    val up = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, x, y, 0)
                    assertTrue(view.dispatchTouchEvent(down))
                    assertTrue(view.dispatchTouchEvent(up))
                    down.recycle(); up.recycle()
                    ShadowLooper.idleMainLooper()
                }
                assertEquals(points.size, shadowOf(app).broadcastIntents.count { it.action == action } - before)
            }
        } finally { activityController.pause().stop().destroy(); session.release(extension) }
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
        assertFalse(view.findViewById<View>(R.id.camera_shutter).isEnabled)
        assertFalse(view.findViewById<View>(R.id.camera_photo).isEnabled)
        assertFalse(view.findViewById<View>(R.id.camera_video).isEnabled)
        assertFalse(CameraSession.get(app).inUse())
        val previewCount = updates.size
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
        assertEquals(previewCount, updates.size)
        emitter.cancel()
        ShadowLooper.idleMainLooper()
        val count = updates.size
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(count, updates.size)
    }

    @Test fun unchangedFieldsDoNotResendAndChangedFieldsStillExpireWithoutStatus() {
        val app = RuntimeEnvironment.getApplication()
        val extension = Robolectric.buildService(CrowExtension::class.java).get()
        val session = CameraSession.get(app)
        val link = session.link
        link.ready = true; link.mode = 1; link.state = 1; link.battery = 85
        link.videoSecondsRemaining = 5070; link.statusAt = SystemClock.elapsedRealtime()
        val emitters = mutableListOf<ViewEmitter>()
        val updates = mutableMapOf<String, MutableList<Bundle>>()
        try {
            for (id in listOf("camera-battery", "camera-video-left", "camera-record")) {
                val values = mutableListOf<Bundle>(); updates[id] = values
                val emitter = ViewEmitter(app.packageName, object : IHandler.Stub() {
                    override fun onNext(bundle: Bundle) { if (bundle.containsKey("view")) values.add(bundle) }
                    override fun onError(message: String) { fail(message) }
                    override fun onComplete() = Unit
                })
                emitters.add(emitter)
                extension.types.single { it.typeId == id }.startView(app, ViewConfig(30 to 15, 240 to 160, 20, preview = false), emitter)
            }
            ShadowLooper.idleMainLooper()
            assertTrue(updates.values.all { it.size == 1 })
            link.seconds = 1; link.videoSecondsRemaining = 5069; extension.changed()
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertTrue(updates.values.all { it.size == 1 })

            link.state = 3; link.seconds = 2; link.statusAt = SystemClock.elapsedRealtime(); extension.changed()
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertEquals(2, updates.getValue("camera-record").size)
            assertEquals(1, updates.getValue("camera-battery").size)
            assertEquals(1, updates.getValue("camera-video-left").size)

            link.battery = 84; link.videoSecondsRemaining = 5039; link.statusAt = SystemClock.elapsedRealtime(); extension.changed()
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertEquals(2, updates.getValue("camera-battery").size)
            assertEquals(2, updates.getValue("camera-video-left").size)
            assertEquals(2, updates.getValue("camera-record").size)

            val receiverField = CrowExtension::class.java.getDeclaredField("screenReceiver").apply { isAccessible = true }
            val screenReceiver = receiverField.get(extension) as BroadcastReceiver
            screenReceiver.onReceive(app, Intent(Intent.ACTION_SCREEN_OFF))
            link.battery = 83; link.statusAt = SystemClock.elapsedRealtime(); extension.changed()
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertEquals(2, updates.getValue("camera-battery").size)
            screenReceiver.onReceive(app, Intent(Intent.ACTION_SCREEN_ON))
            ShadowLooper.idleMainLooper()
            assertEquals(3, updates.getValue("camera-battery").size)

            shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS)
            assertEquals(4, updates.getValue("camera-battery").size)
            @Suppress("DEPRECATION")
            val last = updates.getValue("camera-battery").last().getParcelable<RemoteViews>("view")!!
            assertEquals("Offline", last.apply(app, FrameLayout(app)).findViewById<TextView>(R.id.field_value).text.toString())
        } finally {
            emitters.forEach { it.cancel() }; ShadowLooper.idleMainLooper(); session.release(extension)
        }
    }
}
