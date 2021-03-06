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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import service.BlocklistService
import service.StatsService
import utils.Logger

internal object FilteringService {

    private val log = Logger("Filtering")
    private val blocklist = BlocklistService
    private val stats = StatsService
    private val scope = GlobalScope

    // 所有可以 blocked 的 Host
    private var merged = emptyList<Host>()

    // 用户 主动允许的 Host
    private var userAllowed = emptyList<Host>()

    // 用户 主动拒绝的 Host
    private var userDenied = emptyList<Host>()

    fun reload() {
        log.v("Reloading blocklist")
        merged = blocklist.loadMerged()
//        log.w("-------------------------")
//        merged.forEach { log.w("reload: merged = $it") }

        userAllowed = blocklist.loadUserAllowed()
//        log.w("-------------------------")
//        userAllowed.forEach { log.w("reload: userAllowed = $it") }

        userDenied = blocklist.loadUserDenied()
//        log.w("-------------------------")
//        userDenied.forEach { log.w("reload: userDenied = $it") }

        log.v("Reloaded: ${merged.size} hosts, + user: ${userDenied.size} denied, ${userAllowed.size} allowed")
    }

    // 允许 Host
    fun allowed(host: Host): Boolean {
        return if (userAllowed.contains(host)) {
            // 只有 该 host 在 用户允许的列表中，才能 pass
            scope.launch(Dispatchers.Main) {
                stats.passedAllowed(host)
            }
            true
        } else {
            false
        }
    }

    fun denied(host: Host): Boolean {
        return when {
            userDenied.contains(host) -> {
                // 用户主动拒绝
                scope.launch(Dispatchers.Main) {
                    stats.blockedDenied(host)
                }
                true
            }

            merged.contains(host) -> {
                // 可以拒绝
                scope.launch(Dispatchers.Main) {
                    stats.blocked(host)
                }
                true
            }
            else -> {
                // 不在 用户主动禁止的列表中，也不在 默认的block列表中，那么 直接 pass。
                scope.launch(Dispatchers.Main) {
                    stats.passed(host)
                }
                false
            }
        }
    }

}

typealias Host = String
