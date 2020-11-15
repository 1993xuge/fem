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

import androidx.lifecycle.GeneratedAdapter
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.util.*

@JsonClass(generateAdapter = true)
data class Stats(
    // 当前，被允许的次数
    val allowed: Int,
    // 当前 被禁止的次数
    val denied: Int,
    val entries: List<HistoryEntry>
)

@JsonClass(generateAdapter = true)
data class HistoryEntry(

    // 被拦截的host，即name
    val name: String,

    // 历史信息的中的type
    val type: HistoryEntryType,

    //  最新一次 拦截的时间
    val time: Date,

    // 拦截了 多少次
    val requests: Int
)

enum class HistoryEntryType {
    blocked,
    passed,
    passed_allowed, // Passed because its on user Allowed list
    blocked_denied, // Blocked because its on user Denied list
}

@JsonClass(generateAdapter = true)
data class Allowed(val value: List<String>) {

    // 当前列表中包含 name，则直接返回。
    // 否则 将 name添加到 当前Allowed 的列表中，并创建新的
    fun allow(name: String) = when (name) {
        in value -> this
        else -> Allowed(value = value + name)
    }

    // 当前列表中包含 name，将 name 从 当前Allowed 的列表中删除，并创建新的Allowed对象。
    // 否则 直接返回 当前Allowed对象
    fun unallow(name: String) = when (name) {
        in value -> Allowed(value = value - name)
        else -> this
    }

}

@JsonClass(generateAdapter = true)
data class Denied(val value: List<String>) {

    fun deny(name: String) = when (name) {
        in value -> this
        else -> Denied(value = value + name)
    }

    fun undeny(name: String) = when (name) {
        in value -> Denied(value = value - name)
        else -> this
    }

}

@JsonClass(generateAdapter = true)
data class AdsCounter(
    val persistedValue: Long,
    val runtimeValue: Long = 0
) {
    fun get() = persistedValue + runtimeValue

    fun roll() = AdsCounter(persistedValue = persistedValue + runtimeValue)
}
