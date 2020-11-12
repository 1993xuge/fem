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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.BlokadaException
import ui.utils.cause
import ui.utils.now
import utils.Logger
import java.lang.Integer.max
import java.net.InetSocketAddress
import java.net.Socket

/**
 *
 * 性能监控的Service
 *
 * Monitoring performance of a packet loop (any implementation).
 * 监视数据包循环的性能（任何实现）。
 *
 * Will measure DNS query RTT, and count recoverable errors (like network IO errors). If too many
 * errors happen in a short amount of time, it fire onNoConnectivity() event (and cause a restart by
 * PacketLoopService).
 */
object MetricsService {

    internal const val PACKET_BUFFER_SIZE = 1600

    private const val MAX_ONE_WAY_DNS_REQUESTS = 30
    private const val MAX_RECENT_ERRORS = 50

    private val log = Logger("Metrics")
    private val scope = GlobalScope

    private lateinit var onNoConnectivity: () -> Unit

    private var hadAtLeastOneSuccessfulQuery = false
    private var oneWayDnsCounter = 0

    private var queryCounter = 0
    private var printEveryXQuery = 1

    private var cleanLoopCounter = 0
    private var errorCounterBeforeLoop = 0
    private var errorCounter = 0

    private var id: Short? = null
    private var lastTimestamp = 0L

    var lastRtt = 0L
        @Synchronized get
        @Synchronized set

    fun onLoopEnter() {
        errorCounterBeforeLoop = errorCounter
    }

    fun onLoopExit() {
        if (errorCounter == 0) return

        if (errorCounterBeforeLoop == errorCounter) {
            cleanLoopCounter++
        } else {
            cleanLoopCounter = 0
        }

        if (cleanLoopCounter >= MAX_RECENT_ERRORS / 2) {
            log.v("Loop running clean, resetting errorCounter")
            errorCounter = 0
            cleanLoopCounter = 0
        }
    }

    // 收到错误的方法。当 收到的错误次数 大于 50s，就认为 当前是 onNoConnectivity
    fun onRecoverableError(ex: BlokadaException) {
        log.w("Recoverable error occurred (${++errorCounter}): ${ex.localizedMessage}")
        if (errorCounter >= MAX_RECENT_ERRORS) {
            log.e("Connectivity lost, too many errors recently".cause(ex))
            onNoConnectivity()
        }
    }

    // 一次 dns 查询开始了
    fun onDnsQueryStarted(sequence: Short) {
        if (hadAtLeastOneSuccessfulQuery && ++oneWayDnsCounter >= MAX_ONE_WAY_DNS_REQUESTS) {
            // 已经成功获取了 一次查询，并且 一个 网管查询的次数 大于 30次，直接 返回 onNoConnectivity
            log.e("Connectivity lost, $oneWayDnsCounter DNS requests without a response")
            onNoConnectivity()
        }

        if (id == null) {
            id = sequence
            lastTimestamp = now()
        }
    }

    // 一次 dns 查询结束了
    fun onDnsQueryFinished(sequence: Short) {
        oneWayDnsCounter = 0
        if (sequence == id) {
            lastRtt = now() - lastTimestamp
            id = null

            if ((++queryCounter % printEveryXQuery) == 0) {
                log.v("DNS-RTT/DNS-ERR/REC-ERR: ${lastRtt}ms/$oneWayDnsCounter/$errorCounter")
                if (printEveryXQuery < 30) printEveryXQuery++
                queryCounter = 0
            }
        }
    }

    fun startMetrics(onNoConnectivity: () -> Unit) {
        log.v("Started metrics")

        id = null
        lastTimestamp = 0L
        hadAtLeastOneSuccessfulQuery = false
        this.onNoConnectivity = onNoConnectivity
        queryCounter = 0
        printEveryXQuery = 1
        oneWayDnsCounter = 0
        cleanLoopCounter = 0
        errorCounter = 0
        errorCounterBeforeLoop = 0
        lastRtt = 9999 // To signal connection problems in case we don't get any response soon

        // Those connectivity check methods do not work very well so far.
        hadAtLeastOneSuccessfulQuery = true
        //testConnectivityActive()
        //testConnectivityPassive()
    }

    private fun testConnectivityActive() {
        scope.launch(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.soTimeout = 3000
                socket.connect(InetSocketAddress("blokada.org", 80), 3000);
                hadAtLeastOneSuccessfulQuery = true
            } catch (e: Exception) {
                log.e("Timeout pinging home")
                onNoConnectivity()
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun testConnectivityPassive() {
        scope.launch(Dispatchers.IO) {
            delay(5000)
            if (lastRtt < 9999) {
                log.e("Timeout waiting for the first successful query")
                onNoConnectivity()
            } else {
                hadAtLeastOneSuccessfulQuery = true
            }
        }
    }

}