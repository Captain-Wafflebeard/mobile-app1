package com.theftguard.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Optional bridge to Shizuku. When Shizuku is running and the user has granted
 * permission, the alarm can turn mobile data, Wi-Fi and location on - actions a
 * normal app is not allowed to perform. Everything here is best-effort: if
 * Shizuku is absent or the binder is down, the calls quietly do nothing.
 */
object ShizukuManager {

    const val REQUEST_CODE = 4242
    private const val TAG = "ShizukuManager"

    private var service: IUserService? = null
    private var binding = false

    /** What to run once the user service is connected (null = nothing pending). */
    private var pendingSilent: Boolean? = null

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isAvailable() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            if (isAvailable() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            Log.w(TAG, "requestPermission failed", e)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = if (binder != null && binder.pingBinder()) {
                IUserService.Stub.asInterface(binder)
            } else {
                null
            }
            if (pendingSilent != null) {
                val silent = pendingSilent!!
                pendingSilent = null
                applyActions(silent)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private fun bind(context: Context) {
        if (service != null || binding || !hasPermission()) return
        binding = true
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, UserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shizuku")
                .version(3)
            Shizuku.bindUserService(args, connection)
        } catch (e: Throwable) {
            binding = false
            Log.w(TAG, "bindUserService failed", e)
        }
    }

    /**
     * Turn on mobile data, Wi-Fi and location, and set Do Not Disturb to match the
     * run: off for the loud alarm (so it is heard, notably on Samsung), total silence
     * for the covert run. Called when the alarm starts; a no-op without Shizuku.
     */
    fun applyForAlarm(context: Context, silent: Boolean) {
        if (!hasPermission()) return
        if (service != null) {
            applyActions(silent)
        } else {
            pendingSilent = silent
            bind(context.applicationContext)
        }
    }

    private fun applyActions(silent: Boolean) {
        val svc = service ?: return
        runCatching { svc.enableMobileData() }.onFailure { Log.w(TAG, "enableMobileData failed", it) }
        runCatching { svc.enableWifi() }.onFailure { Log.w(TAG, "enableWifi failed", it) }
        runCatching { svc.enableLocation() }.onFailure { Log.w(TAG, "enableLocation failed", it) }
        runCatching {
            if (silent) svc.turnDndTotalSilence() else svc.turnDndOff()
        }.onFailure { Log.w(TAG, "DND change failed", it) }
    }
}
