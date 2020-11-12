/*
 * This file is part of Blokada.
 *
 * Blokada is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Blokada is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Blokada.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright © 2020 Blocka AB. All rights reserved.
 *
 * @author Karol Gusak (karol@blocka.net)
 */

package engine

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import model.BlokadaException
import model.SystemTunnelRevoked
import service.ContextService
import ui.TunnelViewModel
import ui.app
import ui.utils.cause
import utils.Logger
import java.io.FileInputStream
import java.io.FileOutputStream

class SystemTunnel : VpnService() {

    private val log = Logger("SystemTunnel")

    private var binder: SystemTunnelBinder? = null
        @Synchronized get
        @Synchronized set

    private var config: SystemTunnelConfig? = null
        @Synchronized get
        @Synchronized set

    private var reactedToStart = false
        @Synchronized get
        @Synchronized set

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = {
        ContextService.setContext(this)
        log.v("onStartCommand received: $this, intent: $intent")

        if (!reactedToStart) {
            // 此处的目的是 为了 初始化 TunnelViewModel,只有第一次 创建 才需要
            // System calls us twice in a row on boot
            reactedToStart = true

            // This might be a misuse
            val tunnelVM = ViewModelProvider(app()).get(TunnelViewModel::class.java)
            tunnelVM.turnOnWhenStartedBySystem()
        }

        START_STICKY
    }()

    override fun onBind(intent: Intent?): IBinder? {
        if (SYSTEM_TUNNEL_BINDER_ACTION == intent?.action) {
            log.v("onBind received: $this")
            binder = SystemTunnelBinder(this)
            return binder
        }
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (SYSTEM_TUNNEL_BINDER_ACTION == intent?.action) {
            log.v("onUnbind received: $this")
            return true
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        log.v("onDestroy received: $this")
        turnOff()
        binder { it.onTunnelClosed(null) }
        super.onDestroy()
    }

    override fun onRevoke() {
        log.w("onRevoke received: $this")
        turnOff()
        binder { it.onTunnelClosed(SystemTunnelRevoked()) }
        super.onRevoke()
    }

    fun queryConfig() = config

    fun turnOn(): SystemTunnelConfig {

        log.v("Tunnel turnOn() called")
        val tunnelBuilder = super.Builder()
        // 配置 VPN
        binder { it.onConfigureTunnel(tunnelBuilder) }

        log.v("Asking system for tunnel")
        // 通过 builder 打开 Vpn连接
        val descriptor = tunnelBuilder.establish() ?: throw BlokadaException("Tunnel establish() returned no fd")

        val fd = descriptor.fileDescriptor
        val config = SystemTunnelConfig(descriptor, FileInputStream(fd), FileOutputStream(fd))
        this.config = config
        return config
    }

    fun turnOff() {
        log.v("Tunnel turnOff() called")
        config?.let { config ->
            log.v("Closing tunnel descriptors")
            try {
                config.fd.close()
                config.deviceIn.close()
                config.deviceOut.close()
            } catch (ex: Exception) {
                log.w("Could not close SystemTunnel descriptor".cause(ex))
            }
        }

        config = null
    }

    private fun binder(exec: (SystemTunnelBinder) -> Unit) {
        binder?.let(exec) ?: log.e("No binder attached: $this")
    }

}

class SystemTunnelConfig(
    val fd: ParcelFileDescriptor,
    val deviceIn: FileInputStream,
    val deviceOut: FileOutputStream
)

class SystemTunnelBinder(
    val tunnel: SystemTunnel,
    var onTunnelClosed: (exception: BlokadaException?) -> Unit = {},
    var onConfigureTunnel: (vpn: VpnService.Builder) -> Unit = {}
) : Binder()

const val SYSTEM_TUNNEL_BINDER_ACTION = "SystemTunnel"
