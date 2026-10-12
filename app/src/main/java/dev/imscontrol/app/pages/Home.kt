package dev.imscontrol.app.pages

import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import dev.imscontrol.app.BuildConfig
import dev.imscontrol.app.CarrierModer
import dev.imscontrol.app.R
import dev.imscontrol.app.ShizukuStatus
import dev.imscontrol.app.SubscriptionModer
import dev.imscontrol.app.checkShizukuPermission
import dev.imscontrol.app.components.BooleanPropertyView
import dev.imscontrol.app.components.ClickablePropertyView
import dev.imscontrol.app.components.HeaderText
import dev.imscontrol.app.components.StringPropertyView
import dev.imscontrol.app.getLatestAppVersion
import dev.imscontrol.app.uniqueName
import net.swiftzer.semver.SemVer
import rikka.shizuku.Shizuku

const val TAG = "HomeActivity:Home"

@Suppress("ktlint:standard:function-naming")
@Composable
fun Home(navController: NavController) {
    val context = LocalContext.current
    val carrierModer = remember { CarrierModer(context) }
    val scrollState = rememberScrollState()

    var shizukuEnabled by rememberSaveable { mutableStateOf(false) }
    var shizukuGranted by rememberSaveable { mutableStateOf(false) }
    var subscriptions by remember { mutableStateOf(listOf<SubscriptionInfo>()) }
    var deviceIMSEnabled by rememberSaveable { mutableStateOf(false) }

    var isIMSRegistered by remember { mutableStateOf(listOf<Boolean>()) }
    var newerVersion by rememberSaveable { mutableStateOf("") }

    fun loadFlags() {
        shizukuGranted = true
        subscriptions = carrierModer.subscriptions
        deviceIMSEnabled = carrierModer.deviceSupportsIMS

        if (subscriptions.isNotEmpty() && deviceIMSEnabled) {
            isIMSRegistered = subscriptions.map { SubscriptionModer(context, it.subscriptionId).isIMSRegistered }
        }
    }

    LaunchedEffect(Unit) {
        try {
            when (checkShizukuPermission(0)) {
                ShizukuStatus.GRANTED -> {
                    shizukuEnabled = true
                    loadFlags()
                }
                ShizukuStatus.NOT_GRANTED -> {
                    shizukuEnabled = true
                    Shizuku.addRequestPermissionResultListener { _, grantResult ->
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            loadFlags()
                        }
                    }
                }
                else -> {
                    shizukuEnabled = false
                }
            }
        } catch (e: IllegalStateException) {
            shizukuEnabled = false
        }
        getLatestAppVersion(context) {
            Log.d(TAG, "Fetched version $it")
            try {
                val latest = SemVer.parse(it)
                val current = SemVer.parse(BuildConfig.VERSION_NAME)
                if (latest > current) {
                    newerVersion = it
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ignoring unparseable version", e)
            }
        }
    }

    Column(modifier = Modifier.padding(Dp(16f)).verticalScroll(scrollState)) {
        HeaderText(text = stringResource(R.string.version))
        if (newerVersion.isNotEmpty()) {
            ClickablePropertyView(
                label = BuildConfig.VERSION_NAME,
                value = stringResource(R.string.newer_version_available, newerVersion),
            ) {
                val url = "https://github.com/5m2wse50/IMSControl/releases/tag/$newerVersion"
                val i = Intent(Intent.ACTION_VIEW)
                i.data = url.toUri()
                context.startActivity(i, null)
            }
        } else {
            StringPropertyView(label = BuildConfig.VERSION_NAME, value = stringResource(R.string.running_latest_version))
        }
        HeaderText(text = stringResource(R.string.permissions_capabilities))
        BooleanPropertyView(label = stringResource(R.string.shizuku_service_running), toggled = shizukuEnabled)
        BooleanPropertyView(label = stringResource(R.string.shizuku_permission_granted), toggled = shizukuGranted)
        BooleanPropertyView(label = stringResource(R.string.sim_detected), toggled = subscriptions.isNotEmpty())
        BooleanPropertyView(label = stringResource(R.string.volte_supported_by_device), toggled = deviceIMSEnabled)

        for (idx in subscriptions.indices) {
            var isRegistered = false
            if (isIMSRegistered.isNotEmpty()) {
                isRegistered = isIMSRegistered[idx]
            }
            HeaderText(text = stringResource(R.string.ims_status_for, subscriptions[idx].uniqueName))
            BooleanPropertyView(
                label = stringResource(R.string.ims_status),
                toggled = isRegistered,
                trueLabel = stringResource(R.string.registered),
                falseLabel = stringResource(R.string.unregistered),
            )
        }
    }
}
