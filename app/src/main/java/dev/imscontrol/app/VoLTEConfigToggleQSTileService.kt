package dev.imscontrol.app

import android.os.Build
import android.service.quicksettings.Tile
import android.telephony.CarrierConfigManager

class SIM1VoLTEConfigToggleQSTileService : VoLTEConfigToggleQSTileService(0)

class SIM2VoLTEConfigToggleQSTileService : VoLTEConfigToggleQSTileService(1)

open class VoLTEConfigToggleQSTileService(
    simSlotIndex: Int,
) : BaseQSTileService(simSlotIndex) {
    override fun resolveStatus(moder: SubscriptionModer): Boolean? = moder.isVoLteConfigEnabled

    override fun statusText(resolved: Boolean?): Int =
        when (resolved) {
            true -> R.string.enabled
            false -> R.string.disabled
            null -> R.string.unknown
        }

    private fun toggleVoLTEStatus() {
        val moder = this.moder ?: return
        val volteEnabled = this.status ?: return
        moder.updateCarrierConfig(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, !volteEnabled)
        moder.restartIMSRegistration()
        qsTile.state = if (volteEnabled) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            qsTile.subtitle = getString(if (volteEnabled) R.string.disabled else R.string.enabled)
        }
        qsTile.updateTile()
    }

    // Called when the user taps on your tile in an active or inactive state.
    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { toggleVoLTEStatus() }
        } else {
            toggleVoLTEStatus()
        }
    }
}
