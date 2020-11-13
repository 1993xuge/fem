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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import model.BlokadaException
import model.TunnelStatus
import service.ContextService
import ui.utils.cause
import utils.Logger
import java.net.DatagramSocket
import java.net.Socket

/**
 * 直接 操作 系统 VPNService的类
 */
object SystemTunnelService {

    private val log = Logger("SystemTunnel")
    private val context = ContextService

    // 绑定Vpn Service时的 ServiceConnection对象，从其中可以 获取到 Vpn Service 对象的索引
    private var connection: SystemTunnelConnection? = null
        @Synchronized get
        @Synchronized set

    // 当 配置 Vpn时的callback，从 EngineService 透传到 Vpn Service中
    var onConfigureTunnel: (vpn: VpnService.Builder) -> Unit = {}

    // vpn关闭时的callback，当 Vpn Service 销毁时，回调
    var onTunnelClosed = { ex: BlokadaException? -> }

    // 真正的Vpn Service对象
    private var tunnel: SystemTunnel? = null

    // 启动 Vpn，直接 start vpn代理 Service
    fun setup() {
        log.v("Starting SystemTunnel service")
        val ctx = context.requireAppContext()
        val intent = Intent(ctx, SystemTunnel::class.java)
        ctx.startService(intent)
    }

    // 绑定 Vpn Service，并从中 获取 SystemTunnelConfig 对象，
    // 如果 SystemTunnelConfig 对象 不为空，则表明，已经 打开了 Vpn连接
    suspend fun getStatus(): TunnelStatus {
        return try {
            val hasFileDescriptor = getConnection().binder.tunnel.queryConfig() != null
            if (hasFileDescriptor) {
                TunnelStatus.filteringOnly()
            } else {
                TunnelStatus.off()
            }
        } catch (ex: Exception) {
            log.e("Could not get tunnel status".cause(ex))
            TunnelStatus.error(BlokadaException(ex.message ?: "Unknown reason"))
        }
    }

    // 操作 Vpn Service，打开 Vpn连接
    suspend fun open(): SystemTunnelConfig {
        log.v("Received a request to open tunnel")
        try {
            return getConnection().binder.tunnel.turnOn()
        } catch (ex: Exception) {
            log.w("Could not turn on, unbinding to rebind on next attempt: ${ex.message}")
            unbind()
            throw ex
        }
    }

    // 操作 Vpn Service 关闭 Vpn 连接
    suspend fun close() {
        log.v("Received a request to close tunnel")
        getConnection().binder.tunnel.turnOff()
    }

    // 获取 SystemTunnelConfig，实际上 是为了 获取 打开 Vpn连接时的 FileDescriptor
    suspend fun getTunnelConfig(): SystemTunnelConfig {
        return getConnection().binder.tunnel.queryConfig()
            ?: throw BlokadaException("No system tunnel started")
    }

    fun protectSocket(socket: DatagramSocket) {
        // 直接 将 DatagramSocket 中的数据包 发出去，而不走 vpn代理
        tunnel?.protect(socket) ?: log.e("No tunnel reference while called protectSocket()")
    }

    fun protectSocket(socket: Socket) {
        // 直接 将 socket 中的数据包 发出去，而不走 vpn代理
        tunnel?.protect(socket) ?: log.e("No tunnel reference while called protectSocket()")
    }

    // 绑定 VPNService，并获取 SystemTunnelConnection
    private suspend fun getConnection(): SystemTunnelConnection {
        return connection ?: run {
            val deferred = CompletableDeferred<SystemTunnelBinder>()
            val connection = bind(deferred)

            // 等待 Service 绑定成功后，唤醒
            deferred.await()
            log.v("Bound SystemTunnel")

            this.connection = connection
            this.tunnel = connection.binder.tunnel
            connection
        }
    }

    private suspend fun bind(deferred: ConnectDeferred): SystemTunnelConnection {
        log.v("Binding SystemTunnel")
        val ctx = context.requireAppContext()
        val intent = Intent(ctx, SystemTunnel::class.java).apply {
            action = SYSTEM_TUNNEL_BINDER_ACTION
        }

        val connection = SystemTunnelConnection(deferred,
            onConfigureTunnel = {
                this.onConfigureTunnel(it)
            },
            onTunnelClosed = {
                log.w("Tunnel got closed, unbinding (if bound)")
                unbind()
                this.onTunnelClosed(it)
            },
            onConnectionClosed = {
                this.connection = null
            })

        val flag = Context.BIND_AUTO_CREATE or Context.BIND_ABOVE_CLIENT or Context.BIND_IMPORTANT
        if (!ctx.bindService(intent, connection, flag)) {
            deferred.completeExceptionally(BlokadaException("Could not bindService()"))
        } else {


//            delay(3000)
//            if (!deferred.isCompleted) deferred.completeExceptionally(
//                BlokadaException("Timeout waiting for bindService()")
//            )
        }
        return connection
    }

    fun unbind() {
        connection?.let {
            log.v("Unbinding SystemTunnel")
            try {
                val ctx = context.requireAppContext()
                ctx.unbindService(it)
                log.v("unbindService called")
            } catch (ex: Exception) {
                log.w("unbindService failed: ${ex.message}")
            }
        }
        connection = null
    }

}

/**
 * 绑定 Service时需要的 ServiceConnection
 */
private class SystemTunnelConnection(

    // 用以  等待 Service 绑定完成
    private val deferred: ConnectDeferred,

    // 用以 传递 配置 Vpn时的callback
    var onConfigureTunnel: (vpn: VpnService.Builder) -> Unit,

    // Vpn断开时的 callback
    var onTunnelClosed: (exception: BlokadaException?) -> Unit,

    // Service 绑定断开的 callback
    val onConnectionClosed: () -> Unit
) : ServiceConnection {

    private val log = Logger("SystemTunnel")

    lateinit var binder: SystemTunnelBinder

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        this.binder = binder as SystemTunnelBinder
        binder.onConfigureTunnel = onConfigureTunnel
        binder.onTunnelClosed = onTunnelClosed
        deferred.complete(this.binder)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        log.w("onServiceDisconnected")
        onConnectionClosed()
    }

}

private typealias ConnectDeferred = CompletableDeferred<SystemTunnelBinder>