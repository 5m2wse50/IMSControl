package dev.imscontrol.app

import android.annotation.SuppressLint
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.system.Os
import android.telephony.CarrierConfigManager
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

const val TAG = "BrokerInstrumentation"

class BrokerInstrumentation : Instrumentation() {
    private fun activityManager(): IActivityManager =
        IActivityManager.Stub.asInterface(
            ShizukuBinderWrapper(
                SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE),
            ),
        )

    private fun startShellIdentity(am: IActivityManager) {
        try {
            val method =
                am.javaClass.getMethod(
                    "startDelegateShellPermissionIdentity",
                    Int::class.javaPrimitiveType,
                    Array<String>::class.java,
                )
            method.invoke(am, Os.getuid(), null)
        } catch (e: Exception) {
            Log.w(TAG, "startDelegateShellPermissionIdentity failed, trying UiAutomation fallback", e)
            try {
                getUiAutomation().adoptShellPermissionIdentity()
            } catch (fallbackError: Exception) {
                Log.w(TAG, "UiAutomation adopt fallback failed", fallbackError)
            }
        }
    }

    private fun stopShellIdentity(am: IActivityManager) {
        try {
            // Android 17 removed the no-arg overload,
            // so try the int overload first and fall back to the legacy signature.
            try {
                val withUid =
                    am.javaClass.getMethod(
                        "stopDelegateShellPermissionIdentity",
                        Int::class.javaPrimitiveType,
                    )
                withUid.invoke(am, Os.getuid())
                return
            } catch (_: NoSuchMethodException) {
                // Legacy platform, use the no-arg overload below.
            }
            val legacy = am.javaClass.getMethod("stopDelegateShellPermissionIdentity")
            legacy.invoke(am)
        } catch (e: Exception) {
            Log.w(TAG, "stopDelegateShellPermissionIdentity failed, trying UiAutomation fallback", e)
            try {
                getUiAutomation().dropShellPermissionIdentity()
            } catch (fallbackError: Exception) {
                Log.w(TAG, "UiAutomation drop fallback failed", fallbackError)
            }
        }
    }

    private fun isNonPersistentFallbackNeeded(e: SecurityException): Boolean {
        val message = e.message ?: return false
        return message.contains("persistent=true", ignoreCase = true) ||
            message.contains("system app", ignoreCase = true) ||
            message.contains("invoked by shell", ignoreCase = true)
    }

    private fun overrideWithFallback(
        configurationManager: CarrierConfigManager,
        subId: Int,
        values: PersistableBundle?,
    ) {
        try {
            configurationManager.overrideConfig(subId, values, true)
        } catch (e: SecurityException) {
            if (isNonPersistentFallbackNeeded(e)) {
                Log.w(TAG, "persistent=true rejected, retrying with persistent=false", e)
                configurationManager.overrideConfig(subId, values, false)
            } else {
                throw e
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runWithShellIdentity(
        opName: String,
        subId: Int,
        values: Bundle?,
    ) {
        Log.i(TAG, opName)
        val am = activityManager()
        startShellIdentity(am)
        try {
            val configurationManager = this.context.getSystemService(CarrierConfigManager::class.java)
            val overrideValues = values?.let { toPersistableBundle(it, ModerKeys.all) }
            try {
                overrideWithFallback(configurationManager, subId, overrideValues)
            } catch (t: Throwable) {
                Log.e(TAG, "$opName failed", t)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "$opName failed", t)
        } finally {
            Log.i(TAG, "$opName done")
            try {
                stopShellIdentity(am)
            } catch (t: Throwable) {
                Log.w(TAG, "stopShellIdentity threw", t)
            }
        }
    }

    private fun applyConfig(
        subId: Int,
        arguments: Bundle,
    ) {
        runWithShellIdentity("applyConfig", subId, arguments)
    }

    private fun clearConfig(subId: Int) {
        runWithShellIdentity("clearConfig", subId, null)
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)

        if (arguments == null) {
            finish(0, Bundle())
            return
        }

        val clear = arguments.getBoolean(ModerKeys.CLEAR)
        val subId = arguments.getInt(ModerKeys.SUB_ID)

        try {
            if (clear) {
                this.clearConfig(subId)
            } else {
                this.applyConfig(subId, arguments)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate failed", t)
        } finally {
            try {
                finish(0, Bundle())
            } catch (t: Throwable) {
                Log.w(TAG, "finish failed", t)
            }
        }
    }
}
