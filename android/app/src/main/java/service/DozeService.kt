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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import model.BlokadaException
import utils.Logger

/**
 * 监控系统 是否 处于空闲状态。
 *
 * 当状态发生变化时，通过 onDozeChanged 响应
 */
object DozeService {

    private val log = Logger("Doze")
    private lateinit var powerManager: PowerManager

    var onDozeChanged = { isDoze: Boolean -> }

    /**
     * 注册 设备 空闲模式变化的监听 和 获取 PowerManager 对象
     */
    fun setup(ctx: Context) {
        ctx.registerReceiver(DozeReceiver(), IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))
        powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        log.v("Registered DozeReceiver")
    }

    // 设备是否 处于 空闲模式。这种情况发生在设备未使用并且没有长时间不动的情况下，因此它决定进入低功耗状态。
    fun isDoze() = powerManager.isDeviceIdleMode

    // 系统设备 空闲状态发生变化
    internal fun dozeChanged() {
        val doze = powerManager.isDeviceIdleMode
        log.v("Doze changed: $doze")
        onDozeChanged(doze)
    }

    // 确保在 非空闲状态
    fun ensureNotDoze() {
        if (isDoze()) throw BlokadaException("Doze mode detected")
    }
}

class DozeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, p1: Intent) {
        ContextService.setContext(ctx)
        DozeService.dozeChanged()
    }
}
