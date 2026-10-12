package dev.imscontrol.app

class SIM1IMSStatusQSTileService : IMSStatusQSTileService(0)

class SIM2IMSStatusQSTileService : IMSStatusQSTileService(1)

open class IMSStatusQSTileService(
    simSlotIndex: Int,
) : BaseQSTileService(simSlotIndex) {
    override fun resolveStatus(moder: SubscriptionModer): Boolean? = moder.isIMSRegistered

    override fun statusText(resolved: Boolean?): Int =
        when (resolved) {
            true -> R.string.registered
            false -> R.string.unregistered
            null -> R.string.unknown
        }

    override fun onClick() {
        super.onClick()
        moder?.restartIMSRegistration()
        this.refreshTile()
    }
}
