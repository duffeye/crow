package dev.crow.karoo

import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.util.TypedValue
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
class CrowExtension : KarooExtension("crow", "8"), CameraLink.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val timers = mutableSetOf<Runnable>()
    private val session by lazy { CameraSession.get(this) }
    private val karoo by lazy { KarooSystemService(this) }
    private var active = false
    private var destroyed = false
    private var connected = false

    override val types = listOf("camera", "camera-record", "camera-photo", "camera-status", "camera-mode").map { fieldId -> object : DataTypeImpl("crow", fieldId) {
        override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
            val cancelled = AtomicBoolean(false)
            val tick = object : Runnable {
                override fun run() {
                    if (cancelled.get() || destroyed) return
                    emitter.updateView(if (fieldId == "camera") render(context, config) else renderSeparate(context, config, fieldId))
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
                    // Action tiles draw their header inside the same click target; host headers cannot receive our clicks.
                    emitter.onNext(UpdateGraphicConfig(showHeader = fieldId == "camera" || fieldId == "camera-status"))
                    timers.add(tick)
                    tick.run()
                }
            }
        }
    } }

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

    private fun modeName(mode: Int) = when (mode) {
        0 -> "Slow motion"
        1 -> "Video"
        2 -> "Timelapse"
        5 -> "Photo"
        10 -> "Hyperlapse"
        else -> "Mode $mode"
    }

    private fun fieldGravity(config: ViewConfig): Int = Gravity.CENTER_VERTICAL or when (config.alignment) {
        ViewConfig.Alignment.LEFT -> Gravity.LEFT
        ViewConfig.Alignment.CENTER -> Gravity.CENTER_HORIZONTAL
        ViewConfig.Alignment.RIGHT -> Gravity.RIGHT
    }

    private fun bodyHeight(context: Context, config: ViewConfig): Float {
        val metrics = context.resources.displayMetrics
        // ViewConfig describes the complete field. Leave room for the native single-line header and borders.
        val header = 28 * metrics.density * context.resources.configuration.fontScale.coerceAtLeast(1f)
        return (config.viewSize.second - header - 4 * metrics.density).coerceAtLeast(0f)
    }

    private fun showFieldDetail(context: Context, config: ViewConfig) = bodyHeight(context, config) >= 76 * context.resources.displayMetrics.density

    private fun styleValue(context: Context, config: ViewConfig, view: RemoteViews, id: Int, value: String, fitHeight: Boolean = false) {
        val metrics = context.resources.displayMetrics
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, config.textSize.coerceAtLeast(12).toFloat(), metrics)
        }
        val available = (config.viewSize.first - 16 * metrics.density).coerceAtLeast(1f)
        var ratio = (available / paint.measureText(value).coerceAtLeast(1f)).coerceAtMost(1f)
        if (fitHeight) {
            val detail = if (showFieldDetail(context, config)) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, metrics) else 0f
            val availableHeight = (bodyHeight(context, config) - detail - 6 * metrics.density).coerceAtLeast(0f)
            val lineHeight = kotlin.math.ceil(paint.fontMetrics.descent) - kotlin.math.floor(paint.fontMetrics.ascent)
            ratio = minOf(ratio, availableHeight / lineHeight.coerceAtLeast(1f))
            view.setViewVisibility(id, if (availableHeight >= 12 * metrics.density) View.VISIBLE else View.GONE)
        }
        view.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, (config.textSize.coerceAtLeast(12) * ratio).coerceAtLeast(1f))
        view.setInt(id, "setGravity", fieldGravity(config))
    }

    private fun renderSeparate(context: Context, config: ViewConfig, fieldId: String): RemoteViews {
        val view = RemoteViews(context.packageName, R.layout.ride_field)
        val link = if (config.preview) null else session.link
        val fresh = config.preview || link?.fresh() == true
        val mode = link?.mode ?: 1
        val state = link?.state ?: 1
        val busy = link?.busy == true
        val recording = fresh && mode == 1 && state == 3
        val idle = CameraControls.canSwitchMode(fresh, busy, state)
        val battery = if (config.preview) "85%" else if (link != null && link.battery >= 0) "${link.battery}%" else "—"
        var title: String
        var value: String
        var detail: String
        var command: String? = null
        var enabled = false
        when (fieldId) {
            "camera-record" -> {
                title = "Camera recording"
                value = if (recording) "Stop" else "Start"
                detail = if (recording) String.format(Locale.ROOT, "REC %02d:%02d", link!!.seconds / 60, link.seconds % 60) else "Tap to start"
                command = "record"
                enabled = idle || CameraControls.canShutter(fresh, busy, mode, state) && mode == 1
            }
            "camera-photo" -> {
                title = "Still photo"
                value = "Shoot"
                detail = if (state == 3) "Busy capturing" else "Tap to shoot"
                command = "take-photo"
                enabled = idle
            }
            "camera-mode" -> {
                title = "Camera mode"
                value = modeName(mode)
                detail = if (state == 3) "Locked while capturing" else if (mode == 5) "Tap for Video" else "Tap for Photo"
                command = "toggle-mode"
                enabled = idle
            }
            else -> {
                title = "Camera status"
                value = when {
                    recording -> "REC"
                    busy -> "Working…"
                    state == 3 -> "Capturing"
                    state == 0 -> "Screen off"
                    state == 2 -> "Playback"
                    state == 5 -> "Pre-recording"
                    else -> "Ready"
                }
                detail = if (recording) String.format(Locale.ROOT, "%s • %02d:%02d", battery, link!!.seconds / 60, link.seconds % 60) else "$battery • ${modeName(mode)}"
                enabled = true // Read-only: no pending intent is attached.
            }
        }
        if (!fresh) {
            value = if (fieldId == "camera-status") { if (link?.ready == true) "No signal" else "Offline" } else "Offline"
            detail = "Open Crow to reconnect"
            enabled = fieldId == "camera-status"
        } else if (busy && fieldId != "camera-status") detail = "Waiting for camera…"
        val actionField = fieldId != "camera-status"
        val heading = when (fieldId) {
            "camera-record" -> R.string.record_field_name
            "camera-photo" -> R.string.photo_field_name
            "camera-mode" -> R.string.mode_field_name
            else -> R.string.status_field_name
        }
        view.setTextViewText(R.id.field_title, context.getString(heading))
        view.setViewVisibility(R.id.field_header, if (actionField) View.VISIBLE else View.GONE)
        view.setTextViewText(R.id.field_value, value)
        view.setTextViewText(R.id.field_detail, detail)
        view.setTextColor(R.id.field_value, context.getColor(if (recording && fieldId == "camera-status") R.color.field_recording else if (enabled || config.preview) R.color.field_text else R.color.field_disabled))
        styleValue(context, config, view, R.id.field_value, value, fitHeight = true)
        view.setInt(R.id.field_detail, "setGravity", fieldGravity(config))
        view.setBoolean(R.id.field_root, "setEnabled", enabled && !config.preview)
        view.setContentDescription(R.id.field_root, "$title: $value. $detail")
        view.setViewVisibility(R.id.field_detail, if (showFieldDetail(context, config)) View.VISIBLE else View.GONE)
        if (!config.preview && command != null) view.setOnClickPendingIntent(R.id.field_root, action(context, command))
        return view
    }

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
        view.setTextColor(R.id.camera_detail, context.getColor(R.color.field_secondary))
        styleValue(context, config.copy(textSize = minOf(config.textSize, 28)), view, R.id.camera_status, title)
        view.setInt(R.id.camera_detail, "setGravity", fieldGravity(config))
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
