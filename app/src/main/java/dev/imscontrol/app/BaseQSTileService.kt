package dev.imscontrol.app

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.IllegalStateException

abstract class BaseQSTileService(
    private val simSlotIndex: Int,
) : TileService() {
    init {
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")
    }

    protected val moder: SubscriptionModer? get() {
        val carrierModer = CarrierModer(this.applicationContext)

        try {
            if (checkShizukuPermission(0) == ShizukuStatus.GRANTED && carrierModer.deviceSupportsIMS) {
                val sub =
                    carrierModer.getActiveSubscriptionInfoForSimSlotIndex(this.simSlotIndex)
                        ?: return null
                return SubscriptionModer(this.applicationContext, sub.subscriptionId)
            }
        } catch (_: IllegalStateException) {
        }
        return null
    }

    protected abstract fun resolveStatus(moder: SubscriptionModer): Boolean?

    protected abstract fun statusText(resolved: Boolean?): Int

    protected val status: Boolean? get() {
        /*
         * true: enabled
         * false: disabled
         * null: cannot determine status (Shizuku not running or permission not granted, SIM slot not active, ...)
         */
        val moder = this.moder ?: return null
        try {
            return resolveStatus(moder)
        } catch (_: IllegalStateException) {
        }
        return null
    }

    protected fun refreshTile() {
        val status = this.status
        qsTile.state =
            when (status) {
                true -> Tile.STATE_ACTIVE
                false -> Tile.STATE_INACTIVE
                null -> Tile.STATE_UNAVAILABLE
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            qsTile.subtitle = getString(statusText(status))
        }
        qsTile.updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        if (this.status == null) {
            qsTile.state = Tile.STATE_UNAVAILABLE
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        this.refreshTile()
    }
}
