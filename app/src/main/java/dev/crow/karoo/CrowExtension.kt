package dev.crow.karoo

import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Karoo keeps this service bound while the extension is enabled. It owns one shared BLE session. */
class CrowExtension : KarooExtension("crow", "4"), CameraLink.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val timers = mutableSetOf<Runnable>()
    private val session by lazy { CameraSession.get(this) }
    private val karoo by lazy { KarooSystemService(this) }
    private var active = false
    private var destroyed = false
    private var connected = false

    override val types = listOf(object : DataTypeImpl("crow", "camera") {
        override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
            val cancelled = AtomicBoolean(false)
            val tick = object : Runnable {
                override fun run() {
                    if (cancelled.get() || destroyed) return
                    emitter.updateView(render(context, config))
                    main.postDelayed(this, 1000)
                }
            }
            emitter.setCancellable {
                cancelled.set(true)
                main.post { main.removeCallbacks(tick); timers.remove(tick) }
            }
            main.post {
                if (!cancelled.get() && !destroyed) {
                    // Editing a ride profile must never connect to, or control, a real camera.
                    if (!config.preview) ensureSession()
                    emitter.onNext(UpdateGraphicConfig(showHeader = false))
                    timers.add(tick)
                    tick.run()
                }
            }
        }
    })

    override fun onCreate() {
        super.onCreate()
        karoo.connect { isConnected ->
            main.post {
                if (!destroyed) {
                    connected = isConnected
                    if (connected && active) karoo.dispatch(RequestBluetooth("crow-camera"))
                }
            }
        }
    }

    private fun ensureSession() {
        if (active) return
        active = true
        session.acquire(this)
        if (connected) karoo.dispatch(RequestBluetooth("crow-camera"))
        session.reconnectIfAllowed()
    }

    override fun onBonusAction(actionId: String) {
        main.post {
            if (!destroyed && actionId in setOf("shutter", "photo", "video", "reconnect")) {
                ensureSession()
                if (session.permissionsGranted()) CameraActionReceiver.perform(session, actionId)
            }
        }
    }

    private fun action(context: Context, name: String): PendingIntent = PendingIntent.getBroadcast(
        context, name.hashCode(), Intent(context, CameraActionReceiver::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun render(context: Context, config: ViewConfig): RemoteViews {
        val view = RemoteViews(context.packageName, R.layout.ride_camera)
        val preview = config.preview
        val link = if (preview) null else session.link
        val fresh = link?.fresh() == true
        val mode = link?.mode ?: 1
        val state = link?.state ?: 1
        val recording = fresh && mode == 1 && state == 3
        val canShoot = link != null && CameraControls.canShutter(fresh, link.busy, mode, state)
        val canSwitch = link != null && CameraControls.canSwitchMode(fresh, link.busy, state)
        val disconnected = !preview && link?.ready != true
        val title = when {
            preview -> "Crow • Video • 85%"
            !fresh -> "Crow • Camera unavailable"
            else -> "${if (mode == 5) "Photo" else if (mode == 1) "Video" else "Camera"} • ${if (link!!.battery < 0) "—" else "${link.battery}%"}"
        }
        val detail = when {
            preview -> "Ride-screen preview"
            recording -> String.format(Locale.ROOT, "REC %02d:%02d", link!!.seconds / 60, link.seconds % 60)
            fresh -> if (state == 3) "Capturing" else if (link!!.busy) "Waiting for camera…" else "Ready"
            else -> link?.message ?: "Open Crow Camera to pair"
        }
        view.setTextViewText(R.id.camera_status, title)
        view.setTextViewText(R.id.camera_detail, detail)
        view.setTextColor(R.id.camera_detail, if (recording) Color.rgb(180, 0, 0) else Color.BLACK)
        view.setTextViewText(R.id.camera_shutter, if (disconnected) "Reconnect" else CameraControls.shutterLabel(mode, state))
        view.setBoolean(R.id.camera_shutter, "setEnabled", !preview && (canShoot || disconnected))
        view.setBoolean(R.id.camera_photo, "setEnabled", !preview && canSwitch && mode != 5)
        view.setBoolean(R.id.camera_video, "setEnabled", !preview && canSwitch && mode != 1)
        val heightDp = config.viewSize.second / context.resources.displayMetrics.density
        val widthDp = config.viewSize.first / context.resources.displayMetrics.density
        view.setViewVisibility(R.id.camera_modes, if (heightDp >= 180 && widthDp >= 160) View.VISIBLE else View.GONE)
        view.setViewVisibility(R.id.camera_detail, if (heightDp >= 100) View.VISIBLE else View.GONE)
        if (!preview) {
            view.setOnClickPendingIntent(R.id.camera_shutter, action(context, if (disconnected) "reconnect" else "shutter"))
            view.setOnClickPendingIntent(R.id.camera_photo, action(context, "photo"))
            view.setOnClickPendingIntent(R.id.camera_video, action(context, "video"))
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            view.setOnClickPendingIntent(R.id.camera_status, open)
        }
        return view
    }

    override fun changed() = Unit // Each active view refreshes at the SDK's supported 1 Hz.
    override fun found(device: BluetoothDevice, name: String) = Unit

    override fun onDestroy() {
        destroyed = true
        timers.forEach { main.removeCallbacks(it) }
        timers.clear()
        if (active) {
            if (connected) karoo.dispatch(ReleaseBluetooth("crow-camera"))
            session.release(this)
        }
        karoo.disconnect()
        super.onDestroy()
    }
}
