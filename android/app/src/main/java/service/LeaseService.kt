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

package service

import model.*
import repository.BlockaRepository
import ui.utils.cause
import utils.Logger

object LeaseService {

    private val log = Logger("Lease")
    private val blocka = BlockaRepository
    private val env = EnvironmentService

    // 通过 服务端 接口 创建 lease 信息
    suspend fun createLease(config: BlockaConfig, gateway: Gateway): Lease {
        log.w("Creating lease")

        // 创建 lease请求
        val request = LeaseRequest(
            account_id = config.getAccountId(),
            public_key = config.publicKey,
            gateway_id = gateway.public_key,
            alias = env.getDeviceAlias()
        )

        try {
            // 调用 服务端接口 创建 Lease 信息
            return blocka.createLease(request)
        } catch (ex: TooManyDevices) {
            log.w("Too many devices, attempting to remove one lease")
            deleteLeaseWithAliasOfCurrentDevice(config)
            return blocka.createLease(request)
        }
    }

    // 根据 账户id 从服务端 获取 lease信息
    suspend fun fetchLeases(accountId: AccountId): List<Lease> {
        return blocka.fetchLeases(accountId)
    }

    // 从服务端你 删除 lease信息
    suspend fun deleteLease(lease: Lease) {
        log.w("Deleting lease")
        return blocka.deleteLease(lease.account_id, lease)
    }

    suspend fun checkLease(config: BlockaConfig) {
        if (!config.vpnEnabled) {
            return
        }

        config.lease?.let { lease ->
            log.v("Checking lease")
            config.gateway?.let { gateway ->
                try {
                    // 从服务端 获取 当前的 Lease
                    val currentLease = getCurrentLease(config, gateway)
                    if (!currentLease.isActive()) {
                        // Lease 过期了，从服务端 获取 新的
                        log.w("Lease expired, refreshing")
                        blocka.createLease(
                            LeaseRequest(
                                account_id = config.getAccountId(),
                                public_key = config.publicKey,
                                gateway_id = gateway.public_key,
                                alias = env.getDeviceAlias()
                            )
                        )
                    }
                } catch (ex: Exception) {
                    if (lease.isActive()) log.v("Cached is valid, ignoring")
                    else throw BlokadaException("No valid lease found".cause(ex))
                }
            } ?: throw BlokadaException("No gateway set in current BlockaConfig")
        }
    }

    // 从服务端 获取当前的 Lease 信息
    private suspend fun getCurrentLease(config: BlockaConfig, gateway: Gateway): Lease {
        val leases = blocka.fetchLeases(config.getAccountId())

        if (leases.isEmpty()) throw BlokadaException("No leases found for this account")

        val current =
            leases.firstOrNull { it.public_key == config.publicKey && it.gateway_id == gateway.public_key }

        return current ?: throw BlokadaException("No lease found for this device")
    }

    // This is used to automatically clear the max devices limit, in some scenarios
    // 当绑定 太多设备时，会删除 当前账户的lease
    private suspend fun deleteLeaseWithAliasOfCurrentDevice(config: BlockaConfig) {
        log.v("Deleting lease with alias of current device")
        val leases = blocka.fetchLeases(config.getAccountId())
        val lease = leases.firstOrNull { it.alias == env.getDeviceAlias() }
        lease?.let { deleteLease(it) } ?: log.w("No lease with current device alias found")
    }

}