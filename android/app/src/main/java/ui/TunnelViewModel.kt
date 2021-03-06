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

package ui

import androidx.lifecycle.*
import engine.EngineService
import kotlinx.coroutines.launch
import model.*
import service.*
import ui.utils.cause
import utils.Logger

/**
 * This class is responsible for managing the tunnel state and reflecting it on the UI.
 * 这个类 负责管理 tunnel 的状态，并将其反映到UI上。
 *
 * Mainly used in HomeFragment, but also affecting other parts, like Settings.
 * Warning, this class has the highest chance to be the bloated point of the app.
 *
 * 主要被使用在 HomeFragment，但是也会影响其他界面，比如 Settings。
 * 警告，这个类 大概率 会成为 这个APP的风险点。
 */
class TunnelViewModel : ViewModel() {

    private val log = Logger("TunnelViewModel")
    private val persistence = PersistenceService

    // 实际的 引擎 服务
    private val engine = EngineService

    // Vpn权限申请
    private val vpnPerm = VpnPermissionService

    private val lease = LeaseService

    private val _config = MutableLiveData<BlockaConfig>()
    val config: LiveData<BlockaConfig> = _config

    // 引擎的状态信息
    private val _tunnelStatus = MutableLiveData<TunnelStatus>()
    val tunnelStatus: LiveData<TunnelStatus> = _tunnelStatus.distinctUntilChanged()

    init {
        log.v("init")
        engine.onTunnelStoppedUnexpectedly = this::handleTunnelStoppedUnexpectedly

        viewModelScope.launch {
            // 从本地加载 BlockaConfig
            val cfg = persistence.load(BlockaConfig::class)
            log.v("init: BlockaConfig = $cfg")
            _config.value = cfg

            // 现将 Tunnel的状态设置为 off
            log.v("init: update TunnelStatus with off")
            TunnelStatus.off().emit()

            // 如果 BlockaConfig 中表明，之前 Tunnel是被打开的，则进程启动时 也将它 打开
            if (cfg.tunnelEnabled) {
                // 之前 是 打开的状态，所以 现在 也需要 打开
                log.w("init: Starting tunnel after app start, as it was active before")
                turnOnWhenStartedBySystem()
            }
        }
    }

    // 从 engine 中获取最新的状态，如果 开启了vpn，则还需要 check lease
    fun refreshStatus() {
        log.v("refreshStatus")
        viewModelScope.launch {
            log.v("Querying tunnel status")

            log.v("update TunnelStatus with EngineService getTunnelStatus")
            engine.getTunnelStatus().emit()

            _config.value?.let {
                log.v("BlockaConfig value = $it")
                if (it.vpnEnabled) {
                    try {
                        lease.checkLease(it)
                    } catch (ex: Exception) {
                        log.e("Could not check lease when querying status".cause(ex))
                        clearLease()
                    }
                }
            }
        }
    }

    fun goToBackground() {
        log.v("goToBackground")
        viewModelScope.launch {
            engine.goToBackground()
        }
    }

    /**
     * 打开 广告拦截的开关
     * 1、先检查 是否有vpn的权限，如果没有Vpn权限，则直接将状态更新为 noPermissions
     * 2、从 Engine中获取 状态。
     *  2.1）如果 Engine 已经处于打开 或者 正在打开的状态，直接更新 状态
     *  2.2）未打开
     *      2.2.1）将状态更新为 inProgress
     *      2.2.2）startTunnel
     *      2.2.3）将 tunnelEnabled 设置为true
     *      2.2.4）再次 更新状态
     */
    fun turnOn() {
        log.v("turnOn")
        viewModelScope.launch {
            // 1、先 检查是否有vpn的权限
            if (!vpnPerm.hasPermission()) {
                // 没有权限。将状态 更新为没权限，再关闭
                log.v("Requested to start tunnel, no VPN permissions")
                TunnelStatus.noPermissions().emit()
                TunnelStatus.off().emit()
            } else {
                log.v("Requested to start tunnel")
                // 从 Engin中 获取权限
                val s = engine.getTunnelStatus()
                log.v("turnOn: TunnelStatus from engin = $s")

                // 没有处在 激活状态，并且 不是 正在 打开的状态
                if (!s.inProgress && !s.active) {
                    try {
                        // 将 状态更新为 正在进行中
                        TunnelStatus.inProgress().emit()
                        val cfg = _config.value ?: throw BlokadaException("Config not set")
                        log.v("turnOn : cfg = $cfg")

                        // 实际的开启动作
                        if (cfg.vpnEnabled) {
                            // 开启 Tunnel
                            engine.startTunnel(cfg.lease)
                            // 连接 plus模式下的 vpn
                            engine.connectVpn(cfg)
                            // 检查 lease
                            lease.checkLease(cfg)
                        } else {
                            // 开启 Tunnel
                            engine.startTunnel(null)
                        }

                        // 此处 是 唯一将 tunnelEnabled 设置成true的地方
                        cfg.copy(tunnelEnabled = true).emit()

                        // 重新获取 tunnel的状态，并进行更新
                        engine.getTunnelStatus().emit()
                        log.v("Tunnel started successfully")
                    } catch (ex: Exception) {
                        handleException(ex)
                    }
                } else {
                    log.w("Tunnel busy or already active")
                    s.emit()
                }
            }
        }
    }

    /**
     * 关闭广告拦截的开关
     */
    fun turnOff() {
        log.v("turnOff")
        viewModelScope.launch {
            log.v("Requested to stop tunnel")
            val s = engine.getTunnelStatus()
            if (!s.inProgress && s.active) {
                try {
                    TunnelStatus.inProgress().emit()
                    engine.stopTunnel()
                    _config.value?.copy(tunnelEnabled = false)?.emit()
                    engine.getTunnelStatus().emit()
                    log.v("Tunnel stopped successfully")
                } catch (ex: Exception) {
                    handleException(ex)
                }
            } else {
                log.w("Tunnel busy or already stopped")
                s.emit()
            }
        }
    }

    /**
     * 在 plus Button上 将 Gateway 打开
     *
     * 要打开 gateway ，必须满足 以下 条件
     * 1、BlockaConfig 不为空
     * 2、lease 不为空。而 lease 的获取 是与 相关 Gateway
     */
    fun switchGatewayOn() {
        log.v("switchGatewayOn")
        viewModelScope.launch {
            log.v("Requested to switch gateway on")
            val s = engine.getTunnelStatus()

            if (!s.inProgress && s.gatewayId == null) {
                try {
                    TunnelStatus.inProgress().emit()

                    // tunnel 未激活
                    if (!s.active) throw BlokadaException("Tunnel is not active")
                    // BlockaConfig == null
                    val cfg = _config.value ?: throw BlokadaException("BlockaConfig not set")
                    // lease == null
                    if (cfg.lease == null) throw BlokadaException("Lease not set in BlockaConfig")

                    // 重新 打开 Tunnel
                    engine.restartSystemTunnel(cfg.lease)
                    // 连接 vpn
                    engine.connectVpn(cfg)

                    // vpnEnabled 设置为 true
                    cfg.copy(vpnEnabled = true).emit()

                    engine.getTunnelStatus().emit()
                    log.v("Gateway switched on successfully")

                    viewModelScope.launch {
                        try {
                            // Async check lease to not slow down the primary user flow
                            lease.checkLease(cfg)
                        } catch (ex: Exception) {
                            log.w("Could not check lease".cause(ex))
                        }
                    }
                } catch (ex: Exception) {
                    handleException(ex)
                }
            } else {
                log.w("Tunnel busy or already gateway connected")
                s.emit()
            }
        }
    }

    /**
     * 在 plus Button上 将 Gateway 关闭
     */
    fun switchGatewayOff() {
        log.v("switchGatewayOff")
        viewModelScope.launch {
            log.v("Requested to switch gateway off")
            val s = engine.getTunnelStatus()
            if (!s.inProgress && s.gatewayId != null) {
                try {
                    TunnelStatus.inProgress().emit()
                    if (s.active) {
                        engine.disconnectVpn()
                        engine.restartSystemTunnel(null)
                    }
                    _config.value?.copy(vpnEnabled = false)?.emit()
                    engine.getTunnelStatus().emit()
                    log.v("Gateway switched off successfully")
                } catch (ex: Exception) {
                    handleException(ex)
                }
            } else {
                log.w("Tunnel busy or already no gateway")
                s.emit()
            }
        }
    }

    /**
     * 切换 Gateway
     *
     * 使用 传入的网关 和 账户 信息，创建 新的 Lease，并重启 Engine
     *
     * 由于 打开开关后，才能看见切换网关的入口
     */
    fun changeGateway(gateway: Gateway) {
        log.v("changeGateway")
        viewModelScope.launch {
            log.v("Requested to change gateway")

            // engine 当前的状态
            val s = engine.getTunnelStatus()

            if (!s.inProgress) {
                try {
                    // 将 Tunnel的状态 设置为 progress
                    TunnelStatus.inProgress().emit()
                    var cfg = _config.value ?: throw BlokadaException("BlockaConfig not set")

                    // 根据网关获取 lease
                    val lease = lease.createLease(cfg, gateway)

                    // 复制 一份 BlockaConfig。并更新其 vpnEnabled 、lease、gateway
                    cfg = cfg.copy(vpnEnabled = true, lease = lease, gateway = gateway)

                    // 如果当前的状态是  激活状态，并且 gatewayId 不为空
                    if (s.active && s.gatewayId != null) {
                        // 先断开 vpn
                        engine.disconnectVpn()
                    }

                    if (s.active) {
                        // 使用新的  lease ，重启 tunnel
                        engine.restartSystemTunnel(lease)
                    } else {
                        // 直接 启动 tunnel
                        engine.startTunnel(lease)
                    }

                    // 连接 vpn
                    engine.connectVpn(cfg)

                    // 更新 BlockaConfig
                    cfg.emit()
                    engine.getTunnelStatus().emit()
                    log.v("Gateway changed successfully")
                } catch (ex: Exception) {
                    handleException(ex)
                }
            } else {
                // 正在 打开中
                log.w("Tunnel busy")
                s.emit()
            }

        }
    }

    // 当账户信息 发生变化时，调用该方法
    fun checkConfigAfterAccountChanged(account: Account) {
        log.v("checkConfigAfterAccountChanged")
        viewModelScope.launch {
            _config.value?.let {
                if (account.id != it.keysGeneratedForAccountId) {
                    // 最新的 账户信息中 account Id 与 本地的 account id 不一致。
                    log.w("Account ID changed")
                    newKeypair(account.id)
                } else if (it.keysGeneratedForDevice != EnvironmentService.getDeviceId()) {
                    // 设备不一致
                    log.w("Device ID changed")
                    newKeypair(account.id)
                }
            }
        }
    }

    fun clearLease() {
        log.v("clearLease")
        viewModelScope.launch {
            log.v("Clearing lease")
            _config.value?.copy(vpnEnabled = false, lease = null, gateway = null)?.emit()
            val s = engine.getTunnelStatus()
            if (!s.inProgress && s.gatewayId != null) {
                try {
                    TunnelStatus.inProgress().emit()
                    if (s.active) {
                        engine.disconnectVpn()
                        engine.restartSystemTunnel(null)
                    }
                    engine.getTunnelStatus().emit()
                    log.v("Disconnected from VPN")
                } catch (ex: Exception) {
                    handleException(ex)
                }
            } else {
                log.w("Tunnel busy")
                s.emit()
            }
        }
    }

    private var turnedOnAfterStartedBySystem = false
    fun turnOnWhenStartedBySystem() {
        log.v("turnOnWhenStartedBySystem:turnedOnAfterStartedBySystem = $turnedOnAfterStartedBySystem")
        viewModelScope.launch {
            log.v("turnOnWhenStartedBySystem:_tunnelStatus = ${_tunnelStatus.value}")
            _tunnelStatus.value?.let { status ->
                if (!status.inProgress && !turnedOnAfterStartedBySystem) {
                    turnedOnAfterStartedBySystem = true
                    log.w("turnOnWhenStartedBySystem: System requested to start tunnel, setting up")
                    turnOn()
                }
            }
        }
    }

    fun setInformedUserAboutError() {
        log.v("setInformedUserAboutError")
        viewModelScope.launch {
            log.v("User has been informed about the error")
            engine.getTunnelStatus().emit()
        }
    }

    //
    fun isMe(publicKey: PublicKey): Boolean {
        return publicKey == _config.value?.publicKey
    }

    // 判断 传入的 gatewayId 是否是 当前正在使用的 gatewayId
    fun isCurrentlySelectedGateway(gatewayId: GatewayId): Boolean {
        return gatewayId == _config.value?.gateway?.public_key
    }

    private fun handleException(ex: Exception) {
        log.e("Tunnel failure".cause(ex))
        TunnelStatus.error(TunnelFailure(ex)).emit()
    }

    private fun handleTunnelStoppedUnexpectedly(ex: BlokadaException) {
        log.v("handleTunnelStoppedUnexpectedly")
        viewModelScope.launch {
            log.e("Engine reports tunnel stopped unexpectedly".cause(ex))
            TunnelStatus.error(ex).emit()
            //engine.getTunnelStatus().emit()
        }
    }

    // 创建 针对 该 用户id的 公钥和私钥，并更新 到 BlockaConfig中。
    private suspend fun newKeypair(accountId: AccountId) {
        log.v("newKeypair: accountId = $accountId")
        _config.value?.let {
            try {
                log.w("Generating new keypair")
                val keypair = engine.newKeypair()
                val newConfig = it.copy(
                    privateKey = keypair.first,
                    publicKey = keypair.second,
                    keysGeneratedForAccountId = accountId,
                    keysGeneratedForDevice = EnvironmentService.getDeviceId()
                )
                updateLiveData(newConfig)
                BackupService.requestBackup()
            } catch (ex: Exception) {
                log.e("Could not generate new keypair".cause(ex))
            }
        }
    }

    private fun updateLiveData(config: BlockaConfig) {
        log.v("updateLiveData:config=$config")
        persistence.save(config)
        viewModelScope.launch {
            _config.value = config
        }
    }

    private fun TunnelStatus.emit() {
        _tunnelStatus.value = this
    }

    private fun BlockaConfig.emit() {
        _config.value = this
        persistence.save(this)
    }

}