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

import com.squareup.moshi.JsonClass
import service.EnvironmentService

@JsonClass(generateAdapter = true)
data class BlockaConfig(
    // 私钥， 通过 JNI 代码生成
    val privateKey: PrivateKey,
    // 公钥，通过 JNI 代码生成
    val publicKey: PublicKey,

    // 当前账户id
    val keysGeneratedForAccountId: AccountId,

    // 设备id，所谓的设备id是 设备的品牌+型号
    val keysGeneratedForDevice: DeviceId,

    // 当前账户的Lease，从服务端 获取，可以没有
    // 根据账户的id 、 公钥、网关id、设备别名等信息 获取
    val lease: Lease?,

    // 网关？？？ 从哪里 获取的？？？
    val gateway: Gateway?,

    // 是否 开启 vpn
    val vpnEnabled: Boolean,

    // tunnel 是否 打开了
    val tunnelEnabled: Boolean = false
) {
    fun getAccountId() = keysGeneratedForAccountId
}

// These settings are never backed up to the cloud
// 本地设置？
@JsonClass(generateAdapter = true)
data class LocalConfig(

    // dns
    val dnsChoice: DnsId,

    // 是否 使用 外部浏览器
    val useChromeTabs: Boolean = false,

    // 是否 使用 Dark 模式
    val useDarkTheme: Boolean? = null,

    // 主题
    val themeName: String? = null,

    // 语言
    val locale: String? = null,

    // 是否允许 ipv6
    val ipv6: Boolean = true,

    // 是否 使用 云备份
    val backup: Boolean = true,

    // 是否使用 doh
    val useDnsOverHttps: Boolean = false,

    // 是否 在 plus模式中 使用 BlockaDns
    val useBlockaDnsInPlusMode: Boolean = true,

    // 是否是 躲避版本
    val escaped: Boolean = false,

    // 是否使用前台Service
    val useForegroundService: Boolean = false
)

// These settings are always backed up to the cloud (if possible)
@JsonClass(generateAdapter = true)
data class SyncableConfig(
    val rateAppShown: Boolean,
    val notFirstRun: Boolean,
    val rated: Boolean = false
)
