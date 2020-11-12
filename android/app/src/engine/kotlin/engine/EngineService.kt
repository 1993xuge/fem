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

import com.cloudflare.app.boringtun.BoringTunJNI
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.*
import newengine.BlockaDnsService
import service.EnvironmentService
import service.PersistenceService
import utils.Logger
import java.net.DatagramSocket
import java.net.Socket

object EngineService {

    private var status = TunnelStatus.off()

    private val log = Logger("Engine")
    private val systemTunnelService = SystemTunnelService
    private val packetLoopService = PacketLoopService
    private val filteringService = FilteringService
    private val dnsMapperService = DnsMapperService
    private val blockaDnsService = BlockaDnsService
    private val systemTunnelConfigurator = SystemTunnelConfigurator
    private val scope = GlobalScope

    private var lease: Lease? = null
    private var blockaConfig: BlockaConfig? = null

    private lateinit var dns: Dns
    private lateinit var dnsForPlusMode: Dns

    // Vpn 服务异常关闭的Callback
    var onTunnelStoppedUnexpectedly = { ex: BlokadaException -> }

    fun setup() {
        log.v("setup")
        JniService.setup()

        packetLoopService.onCreateSocket = {
            log.v("setup: onCreateSocket")
            val socket = DatagramSocket()
            systemTunnelService.protectSocket(socket)
            socket
        }

        packetLoopService.onStoppedUnexpectedly = {
            log.v("setup: onStoppedUnexpectedly")
            scope.launch {
                systemTunnelService.close()
                status = TunnelStatus.off()
                onTunnelStoppedUnexpectedly(BlokadaException("PacketLoop stopped"))
            }
        }

        // 来自 VPNService的 Callback，它在 以下两种情况下，invoke
        // Vpn Service OnDestroy 时，这是 正常的结束，BlokadaException是null
        // Vpn Service onRevoke 时，这是异常的，返回 SystemTunnelRevoked异常
        systemTunnelService.onTunnelClosed = { ex: BlokadaException? ->
            log.e("setup: systemTunnel -> onTunnelClosed-> ex = $ex")
            ex?.let {
                scope.launch {
                    packetLoopService.stop()
                    status = TunnelStatus.off()
                    onTunnelStoppedUnexpectedly(it)
                }
            }
        }
    }

    // 获取当前的 状态
    suspend fun getTunnelStatus(): TunnelStatus {
        packetLoopService.getStatus()?.let {
            // 连接正常
            status = TunnelStatus.connected(it)
        } ?: run {
            // 通过 Vpn服务返回
            status = systemTunnelService.getStatus()
            if (status.active) {
                // Make sure to communicate DoH status too
                status = TunnelStatus.filteringOnly(useDoh(dns))
            }
        }
        return status
    }

    suspend fun goToBackground() {
        // 切换到 后台时 解绑 Service
        systemTunnelService.unbind()
    }

    suspend fun newKeypair(): Pair<PrivateKey, PublicKey> {
        val secret = BoringTunJNI.x25519_secret_key()
        log.w("newKeypair: secret = $secret")
        val public = BoringTunJNI.x25519_public_key(secret)
        log.w("newKeypair: public = $public")
        val secretString = BoringTunJNI.x25519_key_to_base64(secret)
        log.w("newKeypair: secretString = $secretString")
        val publicString = BoringTunJNI.x25519_key_to_base64(public)
        log.w("newKeypair: publicString = $publicString")
        return secretString to publicString
    }

    suspend fun startTunnel(lease: Lease?) {

        log.w("startTunnel: lease = $lease  isSlim = ${EnvironmentService.isSlim()}}")
        status = TunnelStatus.inProgress()
        this.lease = lease

        when {
            // Slim mode，被阉割的版本
            lease == null && EnvironmentService.isSlim() -> {
                val useDoh = useDoh(dns)
                dnsMapperService.setDns(dns, useDoh)

                if (useDoh) blockaDnsService.startDnsProxy(dns)

                // 系统配置 vpn时的回调
                systemTunnelService.onConfigureTunnel = { tun ->
                    val ipv6 = PersistenceService.load(LocalConfig::class).ipv6
                    systemTunnelConfigurator.forLibre(tun, dns, ipv6)
                }

                // 打开 系统 vpn
                val tunnelConfig = systemTunnelService.open()

                packetLoopService.startSlimMode(useDoh, dns, tunnelConfig)
                status = TunnelStatus.filteringOnly(useDoh)
            }
            // Libre mode
            lease == null -> {
                val useDoh = useDoh(dns)
                dnsMapperService.setDns(dns, useDoh)

                if (useDoh) blockaDnsService.startDnsProxy(dns)

                systemTunnelService.onConfigureTunnel = { tun ->
                    val ipv6 = PersistenceService.load(LocalConfig::class).ipv6
                    systemTunnelConfigurator.forLibre(tun, dns, ipv6)
                }

                val tunnelConfig = systemTunnelService.open()
                packetLoopService.startLibreMode(useDoh, dns, tunnelConfig)
                status = TunnelStatus.filteringOnly(useDoh)
            }
            // Plus mode
            else -> {
                val useDoh = useDoh(dnsForPlusMode)
                dnsMapperService.setDns(dnsForPlusMode, useDoh)
                if (useDoh) blockaDnsService.startDnsProxy(dnsForPlusMode)
                systemTunnelService.onConfigureTunnel = { tun ->
                    val ipv6 = PersistenceService.load(LocalConfig::class).ipv6
                    systemTunnelConfigurator.forPlus(tun, ipv6, dnsForPlusMode, lease = lease)
                }
                systemTunnelService.open()
                status = TunnelStatus.filteringOnly(useDoh)
            }
        }
    }

    suspend fun stopTunnel() {
        log.w("stopTunnel")
        status = TunnelStatus.inProgress()
        blockaDnsService.stopDnsProxy()
        packetLoopService.stop()
        systemTunnelService.close()
        status = TunnelStatus.off()
    }

    suspend fun connectVpn(config: BlockaConfig) {
        log.w("connectVpn: config = $config")
        if (!status.active) throw BlokadaException("Wrong tunnel state")
        if (config.gateway == null) throw BlokadaException("No gateway configured")
        status = TunnelStatus.inProgress()
        packetLoopService.startPlusMode(
            useDoh = useDoh(dnsForPlusMode), dnsForPlusMode,
            tunnelConfig = systemTunnelService.getTunnelConfig(),
            privateKey = config.privateKey,
            gateway = config.gateway
        )
        this.blockaConfig = config
        status = TunnelStatus.connected(config.gateway.public_key)
    }

    suspend fun disconnectVpn() {
        log.w("disconnectVpn")
        if (!status.active) throw BlokadaException("Wrong tunnel state")
        status = TunnelStatus.inProgress()
        packetLoopService.stop()
        status = TunnelStatus.filteringOnly(useDoh(dns))
    }

    fun setDns(dns: Dns, dnsForPlusMode: Dns? = null) {
        log.w("setDns: Requested to change DNS: dns=$dns  dnsForPlusMode=$dnsForPlusMode")
        this.dns = dns
        this.dnsForPlusMode = dnsForPlusMode ?: dns
    }

    suspend fun changeDns(dns: Dns, dnsForPlusMode: Dns? = null) {
        log.w("changeDns: Requested to change DNS: dns=$dns  dnsForPlusMode=$dnsForPlusMode")
        this.dns = dns
        this.dnsForPlusMode = dnsForPlusMode ?: dns
        restart()
    }

    suspend fun reloadBlockLists() {
        log.w("reloadBlockLists")
        filteringService.reload()
        restart()
    }

    suspend fun restart() {
        log.w("restart")
        val status = getTunnelStatus()
        if (status.active) {
            if (status.gatewayId != null) disconnectVpn()
            restartSystemTunnel(lease)
            if (status.gatewayId != null) connectVpn(blockaConfig!!)
        }
    }

    suspend fun restartSystemTunnel(lease: Lease?) {
        log.w("restartSystemTunnel: lease = $lease")
        stopTunnel()
        log.w("Waiting after stopping system tunnel, before another start")
        delay(5000)
        startTunnel(lease)
    }

    suspend fun pause() {
        throw BlokadaException("TODO pause not implemented")
    }

    fun protectSocket(socket: Socket) {
        log.w("protectSocket: socket = $socket")
        systemTunnelService.protectSocket(socket)
    }

    private fun useDoh(dns: Dns): Boolean {
        log.w("useDoh: dns = $dns")
        return dns.isDnsOverHttps() && PersistenceService.load(LocalConfig::class).useDnsOverHttps
    }
}