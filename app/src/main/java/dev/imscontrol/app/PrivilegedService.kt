package dev.imscontrol.app

import kotlin.system.exitProcess

class PrivilegedService : IPrivilegedService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun exit() {
        this.destroy()
    }

    override fun blah() {
    }
}
