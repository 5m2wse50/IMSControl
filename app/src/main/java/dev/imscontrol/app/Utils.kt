package dev.imscontrol.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.BaseBundle
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.telephony.SubscriptionInfo
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.get
import org.json.JSONArray
import rikka.shizuku.Shizuku
import java.net.HttpURLConnection
import java.net.URL

enum class ShizukuStatus {
    GRANTED,
    NOT_GRANTED,
    STOPPED,
}

fun checkShizukuPermission(code: Int): ShizukuStatus =
    if (Shizuku.getBinder() != null) {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuStatus.GRANTED
        } else {
            if (!Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(0)
            }
            ShizukuStatus.NOT_GRANTED
        }
    } else {
        ShizukuStatus.STOPPED
    }

val SubscriptionInfo.uniqueName: String
    get() = "${this.displayName} (SIM ${this.simSlotIndex + 1})"

private const val RELEASES_URL = "https://api.github.com/repos/5m2wse50/IMSControl/releases"
private const val FALLBACK_VERSION = "0.0.0"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 10_000
private const val VERSION_PREFS = "update_check"
private const val VERSION_TAG_KEY = "latest_tag"
private const val VERSION_AT_KEY = "checked_at_ms"
private const val VERSION_CACHE_TTL_MS = 24L * 60 * 60 * 1000

fun getLatestAppVersion(
    context: Context,
    handler: (String) -> Unit,
) {
    val prefs = context.getSharedPreferences(VERSION_PREFS, Context.MODE_PRIVATE)
    val cachedTag = prefs.getString(VERSION_TAG_KEY, null)
    val checkedAt = prefs.getLong(VERSION_AT_KEY, 0L)
    if (cachedTag != null && System.currentTimeMillis() - checkedAt < VERSION_CACHE_TTL_MS) {
        handler(cachedTag)
        return
    }
    Thread {
        val tag = fetchLatestTag()
        val editor = prefs.edit()
        editor.putString(VERSION_TAG_KEY, tag)
        editor.putLong(VERSION_AT_KEY, System.currentTimeMillis())
        editor.apply()
        Handler(Looper.getMainLooper()).post { handler(tag) }
    }.start()
}

private fun fetchLatestTag(): String {
    var connection: HttpURLConnection? = null
    return try {
        connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            return FALLBACK_VERSION
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        JSONArray(body).getJSONObject(0).getString("tag_name")
    } catch (_: Exception) {
        FALLBACK_VERSION
    } finally {
        connection?.disconnect()
    }
}

fun NavGraphBuilder.composable(
    route: String,
    label: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(@JvmSuppressWildcards NavBackStackEntry) -> Unit,
) {
    addDestination(
        ComposeNavigator.Destination(provider[ComposeNavigator::class], content).apply {
            this.route = route
            this.label = label
            arguments.forEach { (argumentName, argument) ->
                addArgument(argumentName, argument)
            }
            deepLinks.forEach { deepLink ->
                addDeepLink(deepLink)
            }
        },
    )
}

/**
 * Creates a new [PersistableBundle] from the specified [Bundle].
 * Will ignore all values that are not persistable, according
 * to [.isPersistableBundleType], as well as any key in [excludeKeys].
 */
fun toPersistableBundle(
    bundle: Bundle,
    excludeKeys: Set<String> = emptySet(),
): PersistableBundle {
    val persistableBundle = PersistableBundle()
    for (key in bundle.keySet()) {
        if (key in excludeKeys) {
            continue
        }
        val value = bundle.get(key)
        if (isPersistableBundleType(value)) {
            putIntoBundle(persistableBundle, key, value!!)
        }
    }
    return persistableBundle
}

/**
 * Checks if the specified object can be put into a [PersistableBundle].
 *
 * @see [PersistableBundle Implementation](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/os/PersistableBundle.java.49)
 */
fun isPersistableBundleType(value: Any?): Boolean =
    (
        (value is PersistableBundle) ||
            (value is Int) || (value is IntArray) ||
            (value is Long) || (value is LongArray) ||
            (value is Double) || (value is DoubleArray) ||
            (value is String) || (value is Array<*> && value.isArrayOf<String>()) ||
            (value is Boolean) || (value is BooleanArray)
    )

/**
 * Attempts to insert the specified key value pair into the specified bundle.
 *
 * @throws IllegalArgumentException if the value type can not be put into the bundle.
 */
@Throws(IllegalArgumentException::class)
fun putIntoBundle(
    baseBundle: BaseBundle,
    key: String?,
    value: Any?,
) {
    requireNotNull(value != null) { "Unable to determine type of null values" }
    if (value is Int) {
        baseBundle.putInt(key, value)
    } else if (value is IntArray) {
        baseBundle.putIntArray(key, value)
    } else if (value is Long) {
        baseBundle.putLong(key, value)
    } else if (value is LongArray) {
        baseBundle.putLongArray(key, value)
    } else if (value is Double) {
        baseBundle.putDouble(key, value)
    } else if (value is DoubleArray) {
        baseBundle.putDoubleArray(key, value)
    } else if (value is String) {
        baseBundle.putString(key, value)
    } else if (value is Array<*> && value.isArrayOf<String>()) {
        baseBundle.putStringArray(key, value as Array<String?>)
    } else if (value is Boolean) {
        baseBundle.putBoolean(key, value)
    } else if (value is BooleanArray) {
        baseBundle.putBooleanArray(key, value)
    } else {
        throw IllegalArgumentException(
            ("Objects of type ${value?.javaClass?.simpleName ?: "Unknown"} can not be put into a ${BaseBundle::class.java.simpleName}"),
        )
    }
}
