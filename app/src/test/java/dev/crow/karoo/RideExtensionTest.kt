package dev.crow.karoo

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RemoteViews
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class RideExtensionTest {
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
        extension.types.single().startView(app, config, emitter)
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
