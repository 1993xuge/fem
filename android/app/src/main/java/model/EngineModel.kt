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

package model

class TunnelStatus private constructor(
    // 激活状态
    val active: Boolean,
    // 正在打开
    val inProgress: Boolean,
    // 是否使用 uoh
    val isUsingDnsOverHttps: Boolean,
    // ???
    val gatewayId: GatewayId?,
    //
    val error: BlokadaException?,

    val pauseSeconds: Int
) {

    companion object {
        // 开关关闭状态
        fun off() = TunnelStatus(
            active = false,
            inProgress = false,
            isUsingDnsOverHttps = false,
            gatewayId = null,
            error = null,
            pauseSeconds = 0
        )

        // 开关正在进行的状态
        fun inProgress() = TunnelStatus(
            active = false,
            inProgress = true,
            isUsingDnsOverHttps = false,
            gatewayId = null,
            error = null,
            pauseSeconds = 0
        )

        // ???
        fun filteringOnly(doh: Boolean = false) = TunnelStatus(
            active = true,
            inProgress = false,
            isUsingDnsOverHttps = doh,
            gatewayId = null,
            error = null,
            pauseSeconds = 0
        )

        // 已连接 状态
        fun connected(gatewayId: GatewayId) = TunnelStatus(
            active = true,
            inProgress = false,
            isUsingDnsOverHttps = false,
            gatewayId = gatewayId,
            error = null,
            pauseSeconds = 0
        )

        // 没有权限
        fun noPermissions() = TunnelStatus(
            active = false,
            inProgress = false,
            isUsingDnsOverHttps = false,
            gatewayId = null,
            error = NoPermissions(),
            pauseSeconds = 0
        )

        // 异常
        fun error(ex: BlokadaException) = TunnelStatus(
            active = false,
            inProgress = false,
            isUsingDnsOverHttps = false,
            gatewayId = null,
            error = ex,
            pauseSeconds = 0
        )

    }

    override fun toString(): String {
        return "TunnelStatus(active=$active, inProgress=$inProgress, " +
                "isUsingDnsOverHttps=$isUsingDnsOverHttps, gatewayId=$gatewayId, " +
                "error=$error, pauseSeconds=$pauseSeconds)"
    }
}