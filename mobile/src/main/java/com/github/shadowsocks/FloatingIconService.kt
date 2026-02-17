package com.github.shadowsocks

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.github.shadowsocks.aidl.IShadowsocksService
import com.github.shadowsocks.aidl.ShadowsocksConnection
import com.github.shadowsocks.aidl.TrafficStats
import com.github.shadowsocks.bg.BaseService
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.Key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.sql.SQLException

class FloatingIconService : Service(), ShadowsocksConnection.Callback {
    private val notificationId = 2002
    private val connection = ShadowsocksConnection()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var state = BaseService.State.Stopped
    private var view: View? = null
    private var iconView: ImageView? = null
    private var statsView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private var tapPending = false
    private var hasError = false
    private val iconConnected = R.drawable.ic_floating_connected
    private val iconDisconnected = R.drawable.ic_floating_disconnected
    private val iconError = R.drawable.ic_floating_error
    private var lastStats = TrafficStats()
    private var sessionActive = false
    private var sessionStartTime = 0L
    private val uidBaseline = mutableMapOf<Int, Pair<Long, Long>>()
    private val uidPackages = mutableMapOf<Int, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notificationManager = getSystemService(NotificationManager::class.java)
        val canNotify = Build.VERSION.SDK_INT < 24 || notificationManager?.areNotificationsEnabled() != false
        if (canNotify) {
            try {
                startForeground(notificationId, buildNotification())
            } catch (_: Throwable) {
            }
        }
        windowManager = getSystemService(WindowManager::class.java)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val iconSize = dp(44)
        params = WindowManager.LayoutParams(
            iconSize,
            iconSize,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            val displayWidth = resources.displayMetrics.widthPixels
            val displayHeight = resources.displayMetrics.heightPixels
            x = (displayWidth - iconSize - dp(8)).coerceAtLeast(0)
            y = (displayHeight * 9 / 10 - iconSize / 2).coerceAtLeast(0)
        }
        val floatingIcon = ImageView(this).apply {
            setImageResource(iconDisconnected)
            alpha = 1f
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        }
        val floatingStats = TextView(this).apply {
            textSize = 9f
            setTextColor(0xFFFFFFFF.toInt())
            isSingleLine = true
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            visibility = View.GONE
            setShadowLayer(2f, 0f, 0f, 0xAA000000.toInt())
        }
        val floatingView = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
            addView(floatingIcon)
            addView(floatingStats, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        }
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleVpn()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                openMain()
                return true
            }
        })
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        floatingView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var downX = 0f
            private var downY = 0f
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                detector.onTouchEvent(event)
                val lp = params ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = lp.x
                        initialY = lp.y
                        downX = event.rawX
                        downY = event.rawY
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                            dragging = true
                        }
                        if (dragging) {
                            val maxX = (resources.displayMetrics.widthPixels - lp.width).coerceAtLeast(0)
                            val maxY = (resources.displayMetrics.heightPixels - lp.height).coerceAtLeast(0)
                            lp.x = (initialX + dx).coerceIn(0, maxX)
                            lp.y = (initialY + dy).coerceIn(0, maxY)
                            windowManager?.updateViewLayout(v, lp)
                        }
                    }
                }
                return true
            }
        })
        view = floatingView
        iconView = floatingIcon
        statsView = floatingStats
        windowManager?.addView(floatingView, params)
        connection.bandwidthTimeout = 500
        connection.connect(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        connection.bandwidthTimeout = 0
        if (sessionActive) endSessionTracking()
        connection.disconnect(this)
        view?.let { windowManager?.removeView(it) }
        view = null
        iconView = null
        statsView = null
        params = null
        windowManager = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        this.state = state
        hasError = msg != null
        if (state == BaseService.State.Connecting || state == BaseService.State.Connected) hasError = false
        updateIcon()
        when (state) {
            BaseService.State.Connected -> startSessionTracking()
            BaseService.State.Stopped -> {
                lastStats = TrafficStats()
                endSessionTracking()
                updateStats()
                if (msg != null) {
                    openMainWithError(msg)
                }
            }
            else -> {}
        }
        updateStats()
    }

    private fun openMainWithError(errorMsg: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("error_msg", errorMsg)
        }
        startActivity(intent)
    }

    override fun trafficUpdated(profileId: Long, stats: TrafficStats) {
        lastStats = stats
        com.github.shadowsocks.summary.SummaryStore.updateActiveStats(stats.txTotal, stats.rxTotal)
        updateStats()
    }

    override fun onServiceConnected(service: IShadowsocksService) {
        state = BaseService.State.entries[service.state]
        updateIcon()
        updateStats()
        if (state == BaseService.State.Connected) startSessionTracking()
        if (tapPending) {
            tapPending = false
            toggleVpn()
        }
    }

    override fun onServiceDisconnected() {
        state = BaseService.State.Stopped
        hasError = false
        updateIcon()
        updateStats()
    }

    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private fun updateIcon() {
        val res = when {
            hasError -> iconError
            state == BaseService.State.Connected -> iconConnected
            else -> iconDisconnected
        }
        iconView?.setImageResource(res)
        iconView?.alpha = if (state == BaseService.State.Connected) 0.75f else 0.45f
    }

    private fun updateStats() {
        val view = statsView ?: return
        if (state == BaseService.State.Connected) {
            val upRate = Formatter.formatFileSize(this, lastStats.txRate)
            val downRate = Formatter.formatFileSize(this, lastStats.rxRate)
            view.text = "↑${getString(R.string.speed, upRate)} ↓${getString(R.string.speed, downRate)}"
            view.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            view.isSelected = true
            view.visibility = View.VISIBLE
        } else {
            view.isSelected = false
            view.ellipsize = android.text.TextUtils.TruncateAt.END
            view.visibility = View.GONE
            view.text = ""
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, "service-floating")
        .setWhen(0)
        .setContentTitle(getString(R.string.floating_mode))
        .setContentText(getString(R.string.floating_notification))
        .setContentIntent(Core.configureIntent(this))
        .setSmallIcon(R.drawable.ic_service_active)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()

    private fun openMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    private fun toggleVpn() {
        val service = connection.service
        if (service == null) {
            tapPending = true
            return
        }
        when (BaseService.State.entries[service.state]) {
            BaseService.State.Connecting, BaseService.State.Connected, BaseService.State.Stopping -> {
                Core.stopService()
            }
            BaseService.State.Stopped -> startVpnWithFirstProfile()
            BaseService.State.Idle -> startVpnWithFirstProfile()
        }
    }

    private fun startVpnWithFirstProfile() {
        if (DataStore.serviceMode == Key.modeVpn && VpnService.prepare(this) != null) {
            val intent = Intent(this, VpnRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        }
        scope.launch {
            try {
                val profileId = ProfileManager.getActiveProfiles()?.firstOrNull()?.id
                    ?: ProfileManager.createProfile().id
                DataStore.profileId = profileId
            } catch (_: IOException) {
            } catch (_: SQLException) {
            }
            withContext(Dispatchers.Main) {
                Core.startService()
            }
        }
    }

    private fun startSessionTracking() {
        if (sessionActive) return
        sessionActive = true
        sessionStartTime = System.currentTimeMillis()
        uidBaseline.clear()
        uidPackages.clear()
        val apps = packageManager.getInstalledApplications(0)
        apps.forEach { app ->
            val uid = app.uid
            if (!uidPackages.containsKey(uid)) {
                uidPackages[uid] = app.packageName
                val tx = android.net.TrafficStats.getUidTxBytes(uid)
                val rx = android.net.TrafficStats.getUidRxBytes(uid)
                if (tx >= 0 && rx >= 0) {
                    uidBaseline[uid] = tx to rx
                }
            }
        }
        com.github.shadowsocks.summary.SummaryStore.startSession()
    }

    private fun endSessionTracking() {
        if (!sessionActive) return
        sessionActive = false
        val appUsage = mutableMapOf<String, Pair<Long, Long>>()
        uidBaseline.forEach { (uid, baseline) ->
            val tx = android.net.TrafficStats.getUidTxBytes(uid)
            val rx = android.net.TrafficStats.getUidRxBytes(uid)
            if (tx >= 0 && rx >= 0) {
                val dTx = (tx - baseline.first).coerceAtLeast(0)
                val dRx = (rx - baseline.second).coerceAtLeast(0)
                if (dTx + dRx > 0) {
                    val pkg = uidPackages[uid] ?: return@forEach
                    val prev = appUsage[pkg]
                    if (prev == null) appUsage[pkg] = dTx to dRx
                    else appUsage[pkg] = prev.first + dTx to prev.second + dRx
                }
            }
        }
        com.github.shadowsocks.summary.SummaryStore.endSession(
            sessionStartTime,
            System.currentTimeMillis(),
            lastStats.txTotal,
            lastStats.rxTotal,
            appUsage
        )
        uidBaseline.clear()
        uidPackages.clear()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
