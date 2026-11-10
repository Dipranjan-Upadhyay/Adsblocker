package com.adshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Local DNS filter.
 *
 * The VPN routes ONLY one fake DNS address (10.111.222.2) into this app, so normal
 * traffic (video, downloads, browsing) never passes through it: low CPU, low battery,
 * and memory stays small. Every DNS lookup on the phone comes here; ad domains get
 * NXDOMAIN, everything else is forwarded to a real DNS server.
 */
class AdBlockVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.adshield.START"
        const val ACTION_STOP = "com.adshield.STOP"
        private const val TAG = "AdShield"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val DNS_ADDRESS = "10.111.222.2"
        private val UPSTREAM_DNS = arrayOf("1.1.1.1", "9.9.9.9") // Cloudflare, then Quad9
        private const val CHANNEL_ID = "adshield"
        private const val NOTIFICATION_ID = 1

        val blockedCount = AtomicLong()
        @Volatile var isRunning = false
            private set

        fun setEnabled(ctx: Context, enabled: Boolean) =
            ctx.getSharedPreferences("adshield", MODE_PRIVATE).edit()
                .putBoolean("enabled", enabled).apply()

        fun isEnabled(ctx: Context) =
            ctx.getSharedPreferences("adshield", MODE_PRIVATE).getBoolean("enabled", false)
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var pool: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // ACTION_START, a restart by the OS (null intent), or Always-on VPN starting us.
        goForeground()
        if (tun == null && !startVpn()) {
            stopSelf()
            return START_NOT_STICKY
        }
        setEnabled(this, true)
        return START_STICKY // ask Android to recreate us if it ever has to kill the process
    }

    private fun startVpn(): Boolean {
        val blocklist = Blocklist.get(this)
        val fd = try {
            Builder()
                .setSession("AdShield")
                .addAddress(VPN_ADDRESS, 32)
                .addRoute(DNS_ADDRESS, 32)     // only DNS traffic enters the tunnel
                .addDnsServer(DNS_ADDRESS)
                .addDisallowedApplication(packageName) // our own upstream lookups bypass it
                .setBlocking(true)
                .setMetered(false)
                .setMtu(1500)
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e); null
        } ?: return false   // null = VPN permission not granted

        tun = fd
        // Small bounded pool: at most 4 threads, idle threads die after 30 s.
        pool = ThreadPoolExecutor(
            0, 4, 30, TimeUnit.SECONDS, LinkedBlockingQueue(256),
            ThreadPoolExecutor.DiscardOldestPolicy()
        )
        reader = Thread({ readLoop(fd, blocklist) }, "adshield-dns").apply { start() }
        isRunning = true
        return true
    }

    private fun readLoop(fd: ParcelFileDescriptor, blocklist: Set<String>) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(1600) // one packet at a time, reused
        try {
            while (!Thread.interrupted()) {
                val n = input.read(buf)
                if (n <= 0) continue
                val q = DnsQuery.parse(buf, n) ?: continue
                if (Blocklist.isBlocked(blocklist, q.name)) {
                    blockedCount.incrementAndGet()
                    write(output, q.blockedResponse())
                } else {
                    pool?.execute { forward(q, output) }
                }
            }
        } catch (e: IOException) {
            // tun closed: normal on shutdown
        }
    }

    private fun forward(q: DnsQuery, output: FileOutputStream) {
        try {
            DatagramSocket().use { s ->
                protect(s)
                s.soTimeout = 3000
                val resp = ByteArray(4096)
                for (server in UPSTREAM_DNS) {
                    try {
                        s.send(DatagramPacket(q.payload, q.payload.size, InetAddress.getByName(server), 53))
                        val p = DatagramPacket(resp, resp.size)
                        s.receive(p)
                        write(output, q.wrapResponse(resp, p.length))
                        return
                    } catch (_: SocketTimeoutException) {
                        // try next upstream
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "forward failed for ${q.name}: ${e.message}")
        }
    }

    private fun write(output: FileOutputStream, packet: ByteArray) {
        synchronized(output) {
            try { output.write(packet) } catch (_: IOException) {}
        }
    }

    private fun shutdown() {
        isRunning = false
        reader?.interrupt(); reader = null
        pool?.shutdownNow(); pool = null
        try { tun?.close() } catch (_: IOException) {}
        tun = null
    }

    /** Another VPN took over, or the user disconnected us from system settings. */
    override fun onRevoke() {
        setEnabled(this, false)
        shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ad blocking", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("AdShield is blocking ads")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }
}
