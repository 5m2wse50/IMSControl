package dev.imscontrol.app

import android.app.IActivityManager
import android.app.UiAutomationConnection
import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.PersistableBundle
import android.os.ServiceManager
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyFrameworkInitializer
import android.util.Log
import androidx.annotation.RequiresApi
import com.android.internal.telephony.ICarrierConfigLoader
import com.android.internal.telephony.IPhoneSubInfo
import com.android.internal.telephony.ISub
import com.android.internal.telephony.ITelephony
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object InterfaceCache {
    val cache = HashMap<String, IInterface>()
}

object ModerKeys {
    const val CLEAR = "moder_clear"
    const val SUB_ID = "moder_subId"
    val all = setOf(CLEAR, SUB_ID)
}

open class Moder {
    @Suppress("ktlint:standard:property-naming")
    val KEY_IMS_USER_AGENT = "ims.ims_user_agent_string"

    protected inline fun <reified T : IInterface> loadCachedInterface(interfaceLoader: () -> T): T {
        InterfaceCache.cache[T::class.java.name]?.let {
            return it as T
        } ?: run {
            val i = interfaceLoader()
            InterfaceCache.cache[T::class.java.name] = i
            return i
        }
    }

    protected fun systemBinder(
        registerer: () -> IBinder?,
        fallbackServiceName: String,
    ): IBinder =
        try {
            registerer()
        } catch (e: NoClassDefFoundError) {
            ServiceManager.getService(fallbackServiceName)
        }!!

    private val compatMethodCache = ConcurrentHashMap<String, Method?>()

    protected fun invokeCachedMethod(
        receiver: Any,
        methodName: String,
        paramTypes: Array<Class<*>>,
        args: Array<Any?>,
    ): Any? {
        val cacheKey = "${receiver.javaClass.name}#$methodName/${paramTypes.size}"
        val method =
            compatMethodCache.getOrPut(cacheKey) {
                try {
                    receiver.javaClass.getMethod(methodName, *paramTypes)
                } catch (e: NoSuchMethodException) {
                    null
                }
            } ?: return null
        return method.invoke(receiver, *args)
    }

    protected val carrierConfigLoader: ICarrierConfigLoader
        get() =
            ICarrierConfigLoader.Stub.asInterface(
                ShizukuBinderWrapper(
                    systemBinder(
                        { TelephonyFrameworkInitializer.getTelephonyServiceManager().carrierConfigServiceRegisterer.get() },
                        Context.CARRIER_CONFIG_SERVICE,
                    ),
                ),
            )

    protected val telephony: ITelephony
        get() =
            ITelephony.Stub.asInterface(
                ShizukuBinderWrapper(
                    systemBinder(
                        { TelephonyFrameworkInitializer.getTelephonyServiceManager().telephonyServiceRegisterer.get() },
                        Context.TELEPHONY_SERVICE,
                    ),
                ),
            )

    protected val phoneSubInfo: IPhoneSubInfo
        get() =
            IPhoneSubInfo.Stub.asInterface(
                ShizukuBinderWrapper(
                    systemBinder(
                        { TelephonyFrameworkInitializer.getTelephonyServiceManager().phoneSubServiceRegisterer.get() },
                        "iphonesubinfo",
                    ),
                ),
            )

    protected val sub: ISub
        get() =
            ISub.Stub.asInterface(
                ShizukuBinderWrapper(
                    systemBinder(
                        { TelephonyFrameworkInitializer.getTelephonyServiceManager().subscriptionServiceRegisterer.get() },
                        "isub",
                    ),
                ),
            )
}

class CarrierModer(
    private val context: Context,
) : Moder() {
    fun getActiveSubscriptionInfoForSimSlotIndex(index: Int): SubscriptionInfo? {
        val sub = this.loadCachedInterface { sub }
        return try {
            sub.getActiveSubscriptionInfoForSimSlotIndex(index, null, null)
        } catch (e: NoSuchMethodError) {
            invokeCachedMethod(
                sub,
                "getActiveSubscriptionInfoForSimSlotIndex",
                arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, String::class.java),
                arrayOf<Any?>(index, null),
            ) as? SubscriptionInfo
        }
    }

    val subscriptions: List<SubscriptionInfo>
        get() {
            val sub = this.loadCachedInterface { sub }
            try {
                return sub.getActiveSubscriptionInfoList(null, null, true) ?: emptyList()
            } catch (e: NoSuchMethodError) {
            }
            return (
                invokeCachedMethod(
                    sub,
                    "getActiveSubscriptionInfoList",
                    arrayOf<Class<*>>(String::class.java, String::class.java),
                    arrayOf<Any?>(null, null),
                ) as? List<SubscriptionInfo>
                    ?: invokeCachedMethod(
                        sub,
                        "getActiveSubscriptionInfoList",
                        arrayOf<Class<*>>(String::class.java),
                        arrayOf<Any?>(null),
                    ) as? List<SubscriptionInfo>
            ) ?: emptyList()
        }

    val defaultSubId: Int
        get() {
            val sub = this.loadCachedInterface { sub }
            return sub.defaultSubId
        }

    val deviceSupportsIMS: Boolean
        get() {
            val res = Resources.getSystem()
            val volteConfigId = res.getIdentifier("config_device_volte_available", "bool", "android")
            if (volteConfigId == 0) {
                return false
            }
            return res.getBoolean(volteConfigId)
        }
}

class SubscriptionModer(
    private val context: Context,
    val subscriptionId: Int,
) : Moder() {
    @Suppress("ktlint:standard:property-naming")
    private val TAG = "CarrierModer"

    private fun overrideConfigDirectly(bundle: Bundle?) {
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }
        if (bundle != null) {
            val args = toPersistableBundle(bundle)
            iCclInstance.overrideConfig(subscriptionId, args, true)
        } else {
            iCclInstance.overrideConfig(subscriptionId, null, true)
        }
    }

    private fun overrideConfigUsingBroker(bundle: Bundle?) {
        val am =
            IActivityManager.Stub.asInterface(
                ShizukuBinderWrapper(
                    SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE),
                ),
            )

        val arg =
            bundle ?: run {
                val empty = Bundle()
                empty.putBoolean(ModerKeys.CLEAR, true)
                empty
            }
        arg.putInt(ModerKeys.SUB_ID, subscriptionId)

        am.startInstrumentation(
            ComponentName(context, Class.forName("dev.imscontrol.app.BrokerInstrumentation")),
            null,
            8,
            arg,
            null,
            UiAutomationConnection(),
            0,
            null,
        )
    }

    private fun overrideConfig(bundle: Bundle?) {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        val securityPatchDate =
            try {
                sdf.parse(Build.VERSION.SECURITY_PATCH)
            } catch (e: ParseException) {
                null
            }
        if (securityPatchDate == null) {
            this.overrideConfigDirectly(bundle)
        } else {
            cal.time = securityPatchDate
            if (cal.get(Calendar.YEAR) > 2025 || (cal.get(Calendar.YEAR) == 2025 && cal.get(Calendar.MONTH) >= Calendar.OCTOBER)) {
                this.overrideConfigUsingBroker(bundle)
            } else {
                this.overrideConfigDirectly(bundle)
            }
        }
    }

    private fun publishBundle(fn: (Bundle) -> Unit) {
        val overrideBundle = Bundle()
        fn(overrideBundle)
        this.overrideConfig(overrideBundle)
    }

    fun updateCarrierConfig(
        key: String,
        value: Any?,
    ) {
        Log.d(TAG, "Setting $key to $value")
        if (!isPersistableBundleType(value)) {
            throw IllegalArgumentException(
                "Objects of type ${value?.javaClass?.simpleName ?: "Unknown"} cannot be put into a carrier config override",
            )
        }
        publishBundle { putIntoBundle(it, key, value) }
    }

    fun clearCarrierConfig() {
        this.overrideConfig(null)
    }

    fun restartIMSRegistration() {
        val telephony = this.loadCachedInterface { telephony }
        val sub = this.loadCachedInterface { sub }
        telephony.resetIms(sub.getSlotIndex(this.subscriptionId))
    }

    fun getStringValue(key: String): String? {
        Log.d(TAG, "Resolving string value of key $key")
        if (this.subscriptionId < 0) {
            return ""
        }
        return getValue(key) as? String
    }

    fun getBooleanValue(key: String): Boolean {
        Log.d(TAG, "Resolving boolean value of key $key")
        if (this.subscriptionId < 0) {
            return false
        }
        return getValue(key) as? Boolean ?: false
    }

    fun getIntValue(key: String): Int {
        Log.d(TAG, "Resolving integer value of key $key")
        if (this.subscriptionId < 0) {
            return -1
        }
        return getValue(key) as? Int ?: -1
    }

    fun getLongValue(key: String): Long {
        Log.d(TAG, "Resolving long value of key $key")
        if (this.subscriptionId < 0) {
            return -1
        }
        return getValue(key) as? Long ?: -1L
    }

    fun getBooleanArrayValue(key: String): BooleanArray {
        Log.d(TAG, "Resolving boolean array value of key $key")
        if (this.subscriptionId < 0) {
            return booleanArrayOf()
        }
        return getValue(key) as? BooleanArray ?: booleanArrayOf()
    }

    fun getIntArrayValue(key: String): IntArray {
        Log.d(TAG, "Resolving integer value of key $key")
        if (this.subscriptionId < 0) {
            return intArrayOf()
        }
        return getValue(key) as? IntArray ?: intArrayOf()
    }

    @Suppress("UNCHECKED_CAST")
    fun getLongArrayValue(key: String): LongArray {
        Log.d(TAG, "Resolving long array value of key $key")
        if (this.subscriptionId < 0) {
            return longArrayOf()
        }
        return getValue(key) as? LongArray ?: longArrayOf()
    }

    @Suppress("UNCHECKED_CAST")
    fun getStringArrayValue(key: String): Array<String> {
        Log.d(TAG, "Resolving string array value of key $key")
        if (this.subscriptionId < 0) {
            return arrayOf()
        }
        return getValue(key) as? Array<String> ?: emptyArray()
    }

    fun getValue(key: String): Any? {
        Log.d(TAG, "Resolving value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return null
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = this.getConfigForSubId(iCclInstance, subscriptionId)
        return config?.get(key)
    }

    fun getConfigForSubId(
        iCclInstance: ICarrierConfigLoader,
        subscriptionId: Int,
    ): PersistableBundle? {
        try {
            return iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        } catch (e: NoSuchMethodError) {
        }
        return try {
            iCclInstance.getConfigForSubId(subscriptionId, iCclInstance.defaultCarrierServicePackageName)
        } catch (e: NoSuchMethodError) {
            invokeCachedMethod(
                iCclInstance,
                "getConfigForSubId",
                arrayOf<Class<*>>(Int::class.javaPrimitiveType!!),
                arrayOf<Any?>(subscriptionId),
            ) as? PersistableBundle
        }
    }

    val simSlotIndex: Int
        get() = this.loadCachedInterface { sub }.getSlotIndex(subscriptionId)

    val isVoLteConfigEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)

    val isVoNrConfigEnabled: Boolean
        @RequiresApi(VERSION_CODES.UPSIDE_DOWN_CAKE)
        get() =
            this.getBooleanValue(CarrierConfigManager.KEY_VONR_ENABLED_BOOL) &&
                this.getBooleanValue(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL)

    val isCrossSIMConfigEnabled: Boolean
        get() {
            return if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
                this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL) &&
                    this.getBooleanValue(CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL)
            } else {
                false
            }
        }

    val isVoWifiConfigEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL)

    val isVoWifiWhileRoamingEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL)

    val showIMSinSIMInfo: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL)

    val allowAddingAPNs: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_ALLOW_ADDING_APNS_BOOL)

    val showVoWifiMode: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL)

    val showVoWifiRoamingMode: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL)

    val wfcSpnFormatIndex: Int
        get() = this.getIntValue(CarrierConfigManager.KEY_WFC_SPN_FORMAT_IDX_INT)

    val carrierName: String
        get() = this.loadCachedInterface { telephony }.getSubscriptionCarrierName(this.subscriptionId).orEmpty()

    val showVoWifiIcon: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL)

    val alwaysDataRATIcon: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_ALWAYS_SHOW_DATA_RAT_ICON_BOOL)

    val supportWfcWifiOnly: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL)

    val isVtConfigEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL)

    val ssOverUtEnabled: Boolean
        get() =
            if (Build.VERSION.SDK_INT >= VERSION_CODES.Q) {
                this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL)
            } else {
                false
            }

    val ssOverCDMAEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SUPPORT_SS_OVER_CDMA_BOOL)

    val isShow4GForLteEnabled: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SHOW_4G_FOR_LTE_DATA_ICON_BOOL)

    val isHideEnhancedDataIconEnabled: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL)

    val is4GPlusEnabled: Boolean
        get() =
            if (Build.VERSION.SDK_INT >= VERSION_CODES.Q) {
                this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL) &&
                    this.getBooleanValue(CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL) &&
                    !this.getBooleanValue(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL)
            } else {
                this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL) &&
                    !this.getBooleanValue(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL)
            }

    val isNRConfigEnabled: Boolean
        get() =
            if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
                this
                    .getIntArrayValue(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY)
                    .let { it.contains(1) && it.contains(2) }
            } else {
                false
            }

    val userAgentConfig: String
        get() = this.getStringValue(KEY_IMS_USER_AGENT) ?: ""

    val isIMSRegistered: Boolean
        get() {
            val telephony = this.loadCachedInterface { telephony }
            return telephony.isImsRegistered(this.subscriptionId)
        }
}
