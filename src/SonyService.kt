package app.budswidget

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Foreground service holding the link; reconnect schedule 3x3s, 2x5s, 1x15s, then waits for Bluetooth.

class SonyService : Service() {
    companion object {
        const val CH = "sony_status"
        const val NID = 1
    }

    @Volatile private var stop = false
    @Volatile private var client: SonyClient? = null
    private var worker: Thread? = null
    private val wake = LinkedBlockingQueue<Int>()
    private var targetAddr: String? = null

    private val listener: (SonyState) -> Unit = { s ->
        getSystemService(NotificationManager::class.java).notify(NID, buildNotif(Fmt.title(s), Fmt.batt(s)))
    }

    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (d != null && isTarget(d)) {
                LogBuf.add("ACL ${if (i.action == BluetoothDevice.ACTION_ACL_CONNECTED) "connected" else "disconnected"}")
                if (i.action == BluetoothDevice.ACTION_ACL_CONNECTED) wake.offer(1)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun isTarget(d: BluetoothDevice): Boolean = d.address == targetAddr ||
        (targetAddr == null && (d.name?.contains("C710", true) == true || d.name?.contains("WF-", true) == true))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.ensureLoaded(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, Str.get("chan_name"), NotificationManager.IMPORTANCE_LOW))
        val n = buildNotif(Str.get("notif_connecting"), Fmt.batt(Store.state))
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NID, n)

        if (worker == null) {
            stop = false
            Store.listeners.add(listener)
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(aclReceiver, f, Context.RECEIVER_EXPORTED)
            else registerReceiver(aclReceiver, f)
            worker = thread(name = "sony-link") { loop() }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun findDevice(): BluetoothDevice? {
        val a = getSystemService(BluetoothManager::class.java).adapter ?: return null
        val bonded = a.bondedDevices
        return bonded.firstOrNull { it.name?.contains("C710", ignoreCase = true) == true }
            ?: bonded.firstOrNull { it.name?.contains("WF-", ignoreCase = true) == true }
    }

    // retry schedule after a failed/ended link: 3x3s, 2x5s, 1x15s, then park until Bluetooth ACL connects again
    private val retryDelays = intArrayOf(3, 3, 3, 5, 5, 15)

    private fun loop() {
        var attempt = 0
        while (!stop) {
            wake.clear()
            val dev = findDevice()
            var linked = false
            if (dev == null) {
                LogBuf.add("no paired WF-C710N found")
            } else {
                targetAddr = dev.address
                LogBuf.add("target: ${dev.name} ${dev.address}")
                val c = SonyClient(dev, { LogBuf.add(it) }, { st -> Store.set(this, st) }, Store.state)
                client = c; Ctl.client = c
                linked = c.run()
                client = null; Ctl.client = null
                Store.set(this, Store.state.copy(connected = false))
            }
            if (stop) break
            if (linked) attempt = 0
            try {
                if (attempt < retryDelays.size) {
                    val d = retryDelays[attempt++]
                    LogBuf.add("retry $attempt/${retryDelays.size} in ${d}s")
                    if (wake.poll(d.toLong(), TimeUnit.SECONDS) != null) { attempt = 0; Thread.sleep(1500) }
                } else {
                    LogBuf.add("gave up, waiting for earbuds to connect")
                    wake.take(); attempt = 0; Thread.sleep(1500)
                }
            } catch (_: InterruptedException) {}
        }
    }

    private fun buildNotif(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    override fun onDestroy() {
        stop = true
        Store.listeners.remove(listener)
        try { unregisterReceiver(aclReceiver) } catch (_: IllegalArgumentException) {}
        client?.close()
        Ctl.client = null
        wake.offer(1)
        worker?.interrupt()
        worker = null
        Store.set(this, Store.state.copy(connected = false))
        super.onDestroy()
    }
}
