package com.moneymobile.installment.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.moneymobile.installment.monitor.SimWatcher
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.moneymobile.installment.R
import com.moneymobile.installment.admin.DevicePolicyController
import com.moneymobile.installment.api.ApiClient
import com.moneymobile.installment.model.*
import com.moneymobile.installment.ui.LockedActivity
import com.moneymobile.installment.util.AppPreferences
import com.moneymobile.installment.util.CommandSig
import com.moneymobile.installment.util.DeviceInfoCollector
import com.moneymobile.installment.util.LocationCollector
import com.moneymobile.installment.util.LockWarnings
import com.moneymobile.installment.util.OfflineLockGuard
import kotlinx.coroutines.*
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.net.ConnectivityManager
import android.net.Network

class ManagementService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dpc: DevicePolicyController
    @Volatile private var pollingStarted = false
    @Volatile private var locationRefreshing = false
    @Volatile private var simWatchStarted = false
    @Volatile private var offlineWatchStarted = false
    @Volatile private var frpApplyFailed = false
    private val heartbeatLock = Mutex()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Registered at runtime: Android 8+ delivers SIM broadcasts to running apps, not to the manifest.
    private val simReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            serviceScope.launch { SimWatcher.check(applicationContext) }
        }
    }
    private val gson = com.google.gson.Gson()

    companion object {
        private const val TAG = "ManagementService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "mm_management"
        const val ACTION_BOOT = "com.moneymobile.ACTION_BOOT"
        const val ACTION_HEARTBEAT = "com.moneymobile.ACTION_HEARTBEAT"
        const val ACTION_SYNC_NOW = "com.moneymobile.ACTION_SYNC_NOW"

        /** Check in with the shop right now (e.g. just after joining Wi-Fi on the lock screen). */
        fun syncNow(context: Context) {
            try {
                context.startForegroundService(Intent(context, ManagementService::class.java).setAction(ACTION_SYNC_NOW))
            } catch (e: Exception) { Log.w(TAG, "syncNow: ${e.message}") }
        }

        const val ACTION_OFFLINE_CHECK = "com.moneymobile.ACTION_OFFLINE_CHECK"

        /** The payment-deadline alarm fired: check right now whether the phone must lock. */
        fun checkOfflineLock(context: Context) {
            try {
                context.startForegroundService(Intent(context, ManagementService::class.java).setAction(ACTION_OFFLINE_CHECK))
            } catch (e: Exception) { Log.w(TAG, "checkOfflineLock: ${e.message}") }
        }

        const val ACTION_FCM_APPLY = "com.moneymobile.ACTION_FCM_APPLY"
        const val EXTRA_LOCK = "lock"

        /** Apply a Firebase-pushed lock/unlock immediately (already signature-verified). */
        fun applyFromPush(context: Context, lock: Boolean) {
            try {
                context.startForegroundService(
                    Intent(context, ManagementService::class.java).setAction(ACTION_FCM_APPLY).putExtra(EXTRA_LOCK, lock)
                )
            } catch (e: Exception) { Log.w(TAG, "applyFromPush: ${e.message}") }
        }

        fun reportSecurityEvent(context: Context, eventType: String, details: String? = null) {
            val deviceId = AppPreferences.getDeviceId(context) ?: return
            val token = AppPreferences.getDeviceToken(context) ?: return

            val at = if (eventType.contains("SIM", ignoreCase = true)) AppPreferences.getPendingSimEventAt(context) else System.currentTimeMillis()
            CoroutineScope(Dispatchers.IO).launch { sendSecurityEvent(context, deviceId, token, eventType, details, at) }
        }

        /** Returns true once the server has recorded it. A delivered SIM alert clears the pending flag. */
        suspend fun sendSecurityEvent(context: Context, deviceId: String, token: String, eventType: String, details: String?,
                                      happenedAt: Long = System.currentTimeMillis()): Boolean {
            return try {
                val resp = ApiClient.service.reportSecurityEvent(
                    SecurityEventRequest(
                        deviceId = deviceId,
                        deviceToken = token,
                        eventType = eventType,
                        details = details,
                        timestamp = happenedAt,
                        sentAt = System.currentTimeMillis()
                    )
                )
                // 4xx = the server will never accept this one (e.g. stale token): stop retrying so a
                // stuck alert can never block heartbeats forever.
                val ok = resp.isSuccessful || resp.code() in 400..499
                if (ok && eventType.contains("SIM", ignoreCase = true)) {
                    val pending = AppPreferences.getPendingSimEvent(context)
                    if (pending != null && pending.substringBefore('|') == eventType) AppPreferences.setPendingSimEvent(context, null)
                }
                Log.i(TAG, "Security event reported: $eventType ok=$ok")
                ok
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report security event: ${e.message}")
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        dpc = DevicePolicyController(this)
        // Idempotent: makes sure uninstall-block / kiosk allowlist are in place even on
        // phones that were provisioned before this build.
        if (dpc.isDeviceOwner) dpc.applyAllRestrictions()
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // A phone that is not Device Owner has no location permission, which the "location" service
            // type needs on Android 14+. Fall back to the plain data-sync type instead of crashing.
            Log.w(TAG, "startForeground with full types failed (${e.message}) — retrying data sync only")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        }
        try {
            ContextCompat.registerReceiver(
                this, simReceiver,
                IntentFilter().apply {
                    addAction("android.intent.action.SIM_STATE_CHANGED")
                    addAction("android.telephony.action.SIM_CARD_STATE_CHANGED")
                    addAction("android.telephony.action.SIM_APPLICATION_STATE_CHANGED")
                },
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) { Log.w(TAG, "SIM receiver: ${e.message}") }
        // The moment ANY network comes up (Wi-Fi joined on the lock screen, router back on...),
        // check in immediately instead of waiting up to a minute — a queued Unlock lands at once.
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (pollingStarted) serviceScope.launch { delay(2_000L); performHeartbeat() }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) { Log.w(TAG, "network callback: ${e.message}") }
        // Get (or refresh) the Firebase push token so the server can reach this phone.
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
                if (!t.isNullOrBlank() && t != AppPreferences.getFcmToken(this)) {
                    AppPreferences.setFcmToken(this, t); Log.i(TAG, "FCM token cached")
                }
            }
        } catch (e: Exception) { Log.w(TAG, "FCM token: ${e.message}") }
        Log.i(TAG, "ManagementService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        if (AppPreferences.isReleased(this)) {
            Log.i(TAG, "Phone already released — not managing")
            HeartbeatWorker.cancelAll(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // After a reboot (or the service being restarted) re-assert the lock. Only on the first
        // start in this process / on boot, so a running call in the dialer is not interrupted.
        if (AppPreferences.isDeviceLocked(this) && (!pollingStarted || intent?.action == ACTION_BOOT)) {
            dpc.enterLockedMode()
            showLockedScreen()
        }

        // Offline overdue lock: the deadline alarm is lost on reboot, so re-arm it on every start,
        // and lock right away if the deadline already passed (e.g. the phone was off over the date).
        try { offlineLockTick(); OfflineLockGuard.schedule(this) } catch (e: Exception) { Log.w(TAG, "offline lock: ${e.message}") }

        // Online app update: once per service start, settle an update that was in progress (report it
        // installed after the new version started, or failed if it never completed). Never blocks.
        if (!pollingStarted) {
            try { com.moneymobile.installment.update.AppUpdater.onServiceStart(this) } catch (e: Exception) { Log.w(TAG, "app update: ${e.message}") }
        }

        if (intent?.action == ACTION_FCM_APPLY) {
            val lock = intent.getBooleanExtra(EXTRA_LOCK, false)
            Log.i(TAG, "FCM command: ${if (lock) "LOCK" else "UNLOCK"}")
            if (lock) {
                dpc.enterLockedMode(); AppPreferences.setDeviceLocked(this, true); showLockedScreen()
            } else {
                dpc.exitLockedMode(); AppPreferences.setDeviceLocked(this, false)
            }
            // Reconcile + acknowledge with the server when it is reachable (never required to act).
            startPollingLoop(); HeartbeatWorker.schedule(this)
            serviceScope.launch { performHeartbeat() }
            return START_STICKY
        }

        // Fast loop so dashboard data + lock/unlock commands apply within ~1 min
        val alreadyRunning = pollingStarted
        startPollingLoop()
        if (alreadyRunning && (intent?.action == ACTION_SYNC_NOW || intent?.action == ACTION_HEARTBEAT)) {
            serviceScope.launch { performHeartbeat() }
        }

        // Reschedule the slow WorkManager heartbeat as a fallback if the service dies
        HeartbeatWorker.schedule(this)

        return START_STICKY // Restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(simReceiver) } catch (e: Exception) { }
        networkCallback?.let { cb -> try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } catch (e: Exception) { } }
        serviceScope.cancel()
        Log.i(TAG, "ManagementService destroyed — WorkManager will restart heartbeats")
    }

    // ─────────────────────────────────────────────────────────
    // HEARTBEAT — send status, receive commands
    // ─────────────────────────────────────────────────────────

    private fun startSimWatch() {
        if (simWatchStarted) return
        simWatchStarted = true
        serviceScope.launch {
            while (isActive) {
                try { SimWatcher.check(applicationContext) } catch (e: Exception) { Log.w(TAG, "SIM check: ${e.message}") }
                delay(5_000L)
            }
        }
    }

    /**
     * Local backstop for the deadline alarm: a cheap check every 15 s that needs no network, so a
     * phone with no internet still locks within a minute or so of the due time even if the alarm
     * was delayed by battery optimisation.
     */
    private fun startOfflineLockWatch() {
        if (offlineWatchStarted) return
        offlineWatchStarted = true
        serviceScope.launch {
            while (isActive) {
                try { offlineLockTick() } catch (e: Exception) { Log.w(TAG, "offline lock check: ${e.message}") }
                delay(15_000L)
            }
        }
    }

    /** One local pass: lock if the deadline passed, otherwise show a due "phone will lock soon" reminder. */
    private fun offlineLockTick() {
        enforceOfflineLock()
        if (!AppPreferences.isDeviceLocked(this)) LockWarnings.showDue(this)
    }

    /** Locks the phone for an overdue payment using only data already on the phone. */
    private fun enforceOfflineLock() {
        if (AppPreferences.isDeviceLocked(this) || !OfflineLockGuard.isDue(this)) return
        Log.w(TAG, "Payment deadline passed — locking without the server (offline overdue lock)")
        dpc.enterLockedMode()
        AppPreferences.setDeviceLocked(this, true)
        showLockedScreen()
        // The next check-in tells the shop; the server's state then confirms or overrides this.
    }

    private fun startPollingLoop() {
        startSimWatch()
        startOfflineLockWatch()
        if (pollingStarted) return
        pollingStarted = true
        serviceScope.launch {
            while (isActive) {
                performHeartbeat()
                delay(60_000L)
            }
        }
    }

    /** One check-in at a time (the loop, a network change and "sync now" can all ask at once). */
    private suspend fun performHeartbeat() {
        if (!heartbeatLock.tryLock()) return
        try { doHeartbeat() } finally { heartbeatLock.unlock() }
    }

    private suspend fun doHeartbeat() {
        // Registration pending (e.g. the server forgot this phone and it is registering again): the
        // old token is useless, so don't keep sending it every minute.
        if (!AppPreferences.isEnrolled(this)) return
        val deviceId = AppPreferences.getDeviceId(this) ?: run {
            Log.w(TAG, "No device ID — skipping heartbeat")
            return
        }
        val deviceToken = AppPreferences.getDeviceToken(this) ?: run {
            Log.w(TAG, "No device token — skipping heartbeat")
            return
        }

        // Deliver an undelivered SIM alert BEFORE the heartbeat, so the server marks the phone
        // restricted first and the heartbeat's lock state can't undo the local SIM lock.
        // It carries the time the SIM was actually removed: if staff unlocked the phone after
        // that (while it was offline), the server records it without locking again.
        var simAlertHeldSince: Long? = null
        AppPreferences.getPendingSimEvent(this)?.let { pending ->
            val at = AppPreferences.getPendingSimEventAt(this)
            val ok = sendSecurityEvent(this, deviceId, deviceToken, pending.substringBefore('|'), pending.substringAfter('|', ""), at)
            if (!ok) {
                // Previously this skipped the whole check-in, so the dashboard showed "offline" and
                // no Unlock could ever arrive. Now we still check in, but never auto-unlock from it.
                Log.w(TAG, "SIM alert not delivered yet — checking in anyway, keeping the SIM lock")
                simAlertHeldSince = at
            }
        }
        // Tell the shop about an unlock done with an offline code (informational, never locks).
        AppPreferences.getPendingOfflineUnlock(this).takeIf { it > 0 }?.also { at ->
            if (sendSecurityEvent(this, deviceId, deviceToken, "OFFLINE_UNLOCK_CODE", "Unlocked on the phone with a shop unlock code", at)) {
                AppPreferences.setPendingOfflineUnlock(this, 0L)
            }
        }

        try {
            // Send right away with the best cached location; if it is stale, fetch a fresh fix in
            // the background so the NEXT heartbeat has it. Previously this blocked up to 10s.
            val (fix, stale) = LocationCollector.collectCached(this)
            if (stale && !locationRefreshing) {
                locationRefreshing = true
                serviceScope.launch {
                    try { LocationCollector.refresh(this@ManagementService) } finally { locationRefreshing = false }
                }
            }
            Log.i(TAG, "Location fix: ${fix?.lat}, ${fix?.lng} (±${fix?.accuracy}m) stale=$stale")
            // Reset protection as Android holds it right now (shown to the shop as On / Off / Not supported).
            val frpReport = try {
                dpc.frpStatus().let { (st, ids) -> if (st == "off" && frpApplyFailed) "failed" to ids else st to ids }
            } catch (e: Exception) { "failed" to null }
            val request = HeartbeatRequest(
                deviceId = deviceId,
                deviceToken = deviceToken,
                simIccid = DeviceInfoCollector.getSimIccid(this),
                simOperator = DeviceInfoCollector.getSimOperator(this),
                batteryLevel = DeviceInfoCollector.getBatteryLevel(this),
                isConnected = DeviceInfoCollector.isNetworkAvailable(this),
                networkType = DeviceInfoCollector.getNetworkType(this),
                deviceOwner = dpc.isDeviceOwner,
                latitude = fix?.lat,
                longitude = fix?.lng,
                locationAccuracy = fix?.accuracy,
                simState = SimWatcher.stateLabel(this),
                appVersion = com.moneymobile.installment.BuildConfig.VERSION_NAME,
                fcmToken = AppPreferences.getFcmToken(this),
                hasCommandKey = CommandSig.hasKey(this),
                timestamp = System.currentTimeMillis(),
                appVersionCode = com.moneymobile.installment.BuildConfig.VERSION_CODE,
                frpState = frpReport.first,
                frpAccounts = frpReport.second
            )

            val response = ApiClient.service.sendHeartbeat(request)

            if (response.isSuccessful) {
                val body = response.body()
                AppPreferences.setLastHeartbeatTime(this, System.currentTimeMillis())
                // The server hands over the signing key once, to a phone that has none.
                body?.commandKey?.takeIf { it.isNotBlank() }?.let { if (!CommandSig.hasKey(this)) AppPreferences.setCommandKey(this, it) }
                // Only trust the lock state and commands when the server's signature checks out.
                val verifiedAgreement = body?.agreement?.takeIf { CommandSig.agreementOk(this, it) }
                // Reset protection: only a list the server signed (a forged reply can't remove it).
                body?.frpAccountIds?.let { ids ->
                    if (CommandSig.frpOk(this, ids, body.frpSig)) applyFactoryResetProtection(ids)
                    else Log.w(TAG, "Ignoring reset-protection list: bad signature")
                }
                verifiedAgreement?.let {
                    AppPreferences.setAgreementStatusJson(this, gson.toJson(it))
                    // Keep the payment deadline + server clock on the phone for the offline lock.
                    OfflineLockGuard.onSynced(this, it.serverTime, it.overdueLockAt, CommandSig.laterLockAt(this, it))
                }
                Log.i(TAG, "Heartbeat sent — command: ${body?.command?.type}")

                val held = simAlertHeldSince
                body?.command?.takeIf { cmd ->
                    val ok = CommandSig.commandOk(this, cmd)
                    if (!ok) Log.w(TAG, "Ignoring command ${cmd.type}: bad signature")
                    // A command id we already ran is never run twice (replay guard). But the shop only
                    // repeats a command when it never got our confirmation, so confirm Lock/Unlock again.
                    // (Release is never repeated: after it the phone stops checking in.)
                    val repeat = ok && cmd.id == AppPreferences.getLastCmdId(this)
                    if (repeat && (cmd.type == CommandType.LOCK_DEVICE || cmd.type == CommandType.UNLOCK_DEVICE)) {
                        sendAck(cmd.id, deviceId, deviceToken, true, "already applied", attempts = 2)
                    }
                    ok && !repeat
                }?.also { cmd ->
                    AppPreferences.setLastCmdId(this, cmd.id)
                    if (cmd.type == CommandType.UNLOCK_DEVICE && held != null && issuedAtMs(cmd.issuedAt) in 1..held) {
                        Log.w(TAG, "Ignoring Unlock issued before the SIM was removed (alert not delivered yet)")
                    } else if (cmd.type != CommandType.NONE) {
                        executeCommand(cmd, deviceId, deviceToken)
                    }
                }

                // Authoritative reconciliation: the backend's restriction flag is
                // the source of truth. Only act when it is explicitly known (non-null)
                // so an offline blip or missing data never fabricates a lock/unlock.
                // While a SIM alert is undelivered the server doesn't know about the SIM lock yet,
                // so its "unlocked" must not be applied.
                val backendLocked = verifiedAgreement?.locked
                reconcileLockState(if (held != null && backendLocked == false) null else backendLocked)

                // Online app update: start / continue / stop the background update this check-in asks for.
                // Kept last and isolated, so it can never affect locking or commands.
                try { com.moneymobile.installment.update.AppUpdater.onHeartbeat(this, body?.appUpdate) } catch (e: Exception) { Log.w(TAG, "app update: ${e.message}") }
            } else {
                Log.e(TAG, "Heartbeat failed: ${response.code()}")
                // 401 = the server no longer knows this phone (its record was deleted, or it was
                // re-registered elsewhere). Register again by itself using the saved shop setup,
                // instead of staying "Pending" forever.
                if (response.code() == 401 && AppPreferences.isEnrolled(this) && !AppPreferences.getSetupShopId(this).isNullOrBlank()) {
                    Log.w(TAG, "Phone not recognised by server — registering again")
                    AppPreferences.setEnrolled(this, false)
                    com.moneymobile.installment.provisioning.AutoEnrollWorker.enqueue(this)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat exception: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────
    // COMMAND EXECUTION
    // ─────────────────────────────────────────────────────────

    /**
     * Enforce the backend's authoritative restriction flag.
     *  - backendLocked == true  and device not locked  -> lock + show kiosk
     *  - backendLocked == false and device is locked    -> release (kiosk self-exits)
     *  - backendLocked == null  (unknown)               -> do nothing
     */
    private fun reconcileLockState(backendLocked: Boolean?) {
        if (backendLocked == null) return
        val localLocked = AppPreferences.isDeviceLocked(this)
        when {
            backendLocked && !localLocked -> {
                Log.i(TAG, "Reconcile: backend=LOCKED, applying restriction")
                dpc.enterLockedMode()
                AppPreferences.setDeviceLocked(this, true)
                showLockedScreen()
            }
            !backendLocked && localLocked -> {
                Log.i(TAG, "Reconcile: backend=UNLOCKED, releasing restriction")
                dpc.exitLockedMode()
                AppPreferences.setDeviceLocked(this, false)
                // LockedActivity polls isDeviceLocked and exits kiosk itself.
            }
        }
    }

    private suspend fun executeCommand(command: DeviceCommand, deviceId: String, token: String) {
        Log.i(TAG, "Executing command: ${command.type}")
        var executed = false
        var result = "ok"
        var releaseAfterAck = false

        try {
            when (command.type) {
                CommandType.LOCK_DEVICE -> {
                    dpc.enterLockedMode()
                    AppPreferences.setDeviceLocked(this, true)
                    showLockedScreen()
                    // Shown on the web dashboard next to "applied on phone"
                    result = if (dpc.isDeviceOwner) "kiosk active" else "lock screen only - app is not Device Owner"
                    executed = true
                }
                CommandType.UNLOCK_DEVICE -> {
                    dpc.exitLockedMode()
                    AppPreferences.setDeviceLocked(this, false)
                    executed = true
                }
                CommandType.RELEASE_DEVICE -> {
                    // Fully paid: tear everything down, then stop managing this phone.
                    AppPreferences.setDeviceLocked(this, false)
                    dpc.releaseFully()
                    if (dpc.isDeviceOwner) {
                        // Android refused to let go of the phone. Do NOT pretend it was released: keep
                        // managing it so the shop sees the failure and can send Release again.
                        result = if (dpc.frpStatus().first == "on") "release incomplete - reset protection could not be removed, press Release again"
                                 else "release incomplete - app is still device owner"
                        Log.e(TAG, result)
                    } else {
                        AppPreferences.setReleased(this, true)
                        result = "released"
                        executed = true
                        // Ack is sent below; then shut the service and background work down.
                        releaseAfterAck = true
                    }
                }
                CommandType.BLOCK_CALLS -> {
                    dpc.blockOutgoingCalls()
                    executed = true
                }
                CommandType.RESTORE_CALLS -> {
                    dpc.restoreDeviceAccess()
                    executed = true
                }
                CommandType.BLOCK_DATA -> {
                    dpc.blockDataUsage()
                    executed = true
                }
                CommandType.COLLECT_INFO -> {
                    result = collectAndReportInfo()
                    executed = true
                }
                else -> {
                    result = "unknown_command"
                }
            }
        } catch (e: Exception) {
            result = "error: ${e.message}"
            Log.e(TAG, "Command execution failed: ${e.message}")
        }

        // Acknowledge command to backend. Release matters most (the shop marks the phone handed over
        // only on this confirmation), so it keeps trying for about a minute on a weak connection.
        sendAck(command.id, deviceId, token, executed, result, attempts = if (releaseAfterAck) 6 else 3)

        if (releaseAfterAck) {
            Log.i(TAG, "Released — stopping management service and heartbeat")
            HeartbeatWorker.cancelAll(this)
            OfflineLockGuard.cancel(this)
            LockWarnings.cancel(this)
            pollingStarted = false
            serviceScope.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Tells the shop a command was handled. Retries with growing pauses; a 4xx answer is final. */
    private suspend fun sendAck(commandId: String, deviceId: String, token: String, executed: Boolean, result: String, attempts: Int) {
        val pauses = longArrayOf(0L, 3_000L, 6_000L, 12_000L, 24_000L, 30_000L)
        for (i in 0 until attempts.coerceIn(1, pauses.size)) {
            if (pauses[i] > 0) delay(pauses[i])
            try {
                val r = ApiClient.service.acknowledgeCommand(CommandAckRequest(deviceId, token, commandId, executed, result))
                if (r.isSuccessful || r.code() in 400..499) return
                Log.w(TAG, "Ack not accepted yet (${r.code()}) — attempt ${i + 1}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to acknowledge command (attempt ${i + 1}): ${e.message}")
            }
        }
    }

    /** Android 11+: after any factory reset / reflash, only the shop's Google account can set the phone up again. */
    private fun applyFactoryResetProtection(ids: List<String>) {
        try {
            val key = ids.filter { it.isNotBlank() }.sorted().joinToString(",")
            // Skip when already applied — unless Android no longer holds exactly this list (then apply again).
            if (key == (AppPreferences.getFrpApplied(this) ?: "")) {
                val (st, held) = dpc.frpStatus()
                val holdsIt = if (key.isEmpty()) st != "on" else (st == "on" && held == key)
                if (holdsIt || st == "unsupported") return
            }
            if (dpc.applyFactoryResetProtection(ids.filter { it.isNotBlank() })) {
                AppPreferences.setFrpApplied(this, key.ifEmpty { null })
                frpApplyFailed = false
            } else {
                frpApplyFailed = true
            }
        } catch (e: Exception) { Log.w(TAG, "FRP: ${e.message}") }
    }

    private fun issuedAtMs(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return try { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() } catch (e: Exception) { 0L }
    }

    private fun showLockedScreen() {
        val intent = Intent(this, LockedActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }

    private fun collectAndReportInfo(): String {
        val info = DeviceInfoCollector.collect(this, "")
        return "model=${info.model},android=${info.androidVersion},sim=${info.simIccid}"
    }

    // ─────────────────────────────────────────────────────────
    // NOTIFICATION
    // ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Device Management",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors device enrollment status"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Device Management Active")
        .setContentText("Installment agreement monitoring active")
        .setSmallIcon(R.drawable.ic_notification)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setSilent(true)
        .build()
}
