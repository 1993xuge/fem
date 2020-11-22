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

import model.BlokadaException
import ui.utils.cause
import utils.Logger
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import engine.MetricsService.PACKET_BUFFER_SIZE
import org.pcap4j.packet.*
import org.pcap4j.packet.factory.PacketFactoryPropertiesLoader
import org.pcap4j.util.PropertiesLoader
import java.io.*
import java.net.*
import java.nio.ByteBuffer

/**
 * DatagramPacket是基于UDP协议的,
 */
internal class PacketLoopForLibre(
    private val deviceIn: FileInputStream,
    private val deviceOut: FileOutputStream,
    private val createSocket: () -> DatagramSocket,
    private val stoppedUnexpectedly: () -> Unit,
    filter: Boolean = true
) : Thread("PacketLoopForLibre") {

    private val log = Logger("PLLibre")
    private val metrics = MetricsService
    private val forwarder: Forwarder = Forwarder()

    // Memory buffers
    private val buffer = ByteBuffer.allocateDirect(2000)
    private val memory = ByteArray(PACKET_BUFFER_SIZE)
    private val packet = DatagramPacket(memory, 0, 1)

    // 输入的文件描述符
    private var devicePipe: FileDescriptor? = null

    // 记录错误的文件描述符
    private var errorPipe: FileDescriptor? = null

    private val rewriter = PacketRewriter(this::loopback, buffer, filter = filter)

    override fun run() {
        log.v("Started packet loop thread: ${this.hashCode()}")

        try {
            val errors = setupErrorsPipe()
            // 建立
            val device = setupDevicePipe(deviceIn)

            while (true) {
                metrics.onLoopEnter()
                if (shouldInterruptLoop()) throw InterruptedException()

                // 监听管道的 PollIn事件
                device.listenFor(OsConstants.POLLIN)

                val polls = setupPolls(errors, device)
                poll(polls)
                fromOpenSocketsToProxy(polls)
                fromDeviceToProxy(device, deviceIn)
                purge()
                metrics.onLoopExit()
            }
        } catch (ex: InterruptedException) {
            log.v("Tunnel thread interrupted, stopping")
        } catch (ex: Exception) {
            log.w("Unexpected failure, stopping (maybe just closed?) $this: ${ex.message}")
        } finally {
            cleanup()
            if (!isInterrupted) stoppedUnexpectedly()
        }
    }

    private fun fromDevice(fromDevice: ByteArray, length: Int) {
        if (rewriter.handleFromDevice(fromDevice, length)) return

        val originEnvelope = try {
            IpSelector.newPacket(fromDevice, 0, length) as IpPacket
        } catch (ex: Exception) {
            log.w("Failed reading origin packet".cause(ex))
            return
        }

        if (originEnvelope.payload !is UdpPacket) {
            //log.w("Expected UdpPacket but got something else")
            return
        }

        val udp = originEnvelope.payload as UdpPacket

        if (udp.payload == null) {
            // Some apps use empty UDP packets for something good
            log.w("Empty udp packets not handled")
//            val proxiedUdp = DatagramPacket(ByteArray(0), 0, 0, destination.getAddress(),
//                udp.header.dstPort.valueAsInt())
//            forward(proxiedUdp)
            return
        }

        val proxiedDns = DatagramPacket(
            udp.payload.rawData, 0, udp.payload.length(),
            originEnvelope.header.dstAddr,
            udp.header.dstPort.valueAsInt()
        )
        forward(proxiedDns, originEnvelope)
    }

    private fun toDevice(source: ByteArray, length: Int, originEnvelope: Packet) {
        originEnvelope as IpPacket

        // 获取 报文中的数据
        val udp = originEnvelope.payload as UdpPacket

        val udpResponse = UdpPacket.Builder(udp)
            .srcAddr(originEnvelope.header.dstAddr)
            .dstAddr(originEnvelope.header.srcAddr)
            .srcPort(udp.header.dstPort)
            .dstPort(udp.header.srcPort)
            .correctChecksumAtBuild(true)
            .correctLengthAtBuild(true)
            .payloadBuilder(UnknownPacket.Builder().rawData(source))
            .length(length.toShort())

        val envelope: IpPacket
        if (originEnvelope is IpV4Packet) {
            envelope = IpV4Packet.Builder(originEnvelope)
                .srcAddr(originEnvelope.header.dstAddr as Inet4Address)
                .dstAddr(originEnvelope.header.srcAddr as Inet4Address)
                .correctChecksumAtBuild(true)
                .correctLengthAtBuild(true)
                .payloadBuilder(udpResponse)
                .build()
        } else {
            envelope = IpV6Packet.Builder(originEnvelope as IpV6Packet)
                .srcAddr(originEnvelope.header.dstAddr as Inet6Address)
                .dstAddr(originEnvelope.header.srcAddr as Inet6Address)
                .correctLengthAtBuild(true)
                .payloadBuilder(udpResponse)
                .build()
        }

        buffer.clear()
        buffer.put(envelope.rawData)
        buffer.rewind()
        buffer.limit(envelope.rawData.size)

        rewriter.handleToDevice(buffer, envelope.rawData.size)

        loopback()
    }

    /**
     * 创建 socket，并将 数据报文 发送到真实的 网络中。
     */
    private fun forward(udp: DatagramPacket, originEnvelope: IpPacket? = null) {
        val socket = createSocket()
        try {
            // 将数据报包 发送到 真实的网络中
            socket.send(udp)

            if (originEnvelope != null) {
                forwarder.add(socket, originEnvelope)
            } else try {
                socket.close()
            } catch (ex: Exception) {
            }
        } catch (ex: Exception) {
            try {
                socket.close()
            } catch (ex: Exception) {
            }
            handleForwardException(ex)
        }
    }

    private fun loopback() {
        val b = buffer
        deviceOut.write(b.array(), b.arrayOffset() + b.position(), b.limit())
    }

    private fun setupErrorsPipe() = {
        val pipe = Os.pipe()
        errorPipe = pipe[0]

        val errors = StructPollfd()

        // 设置 要轮询的文件描述符。
        errors.fd = errorPipe

        errors.listenFor(OsConstants.POLLHUP or OsConstants.POLLERR)
        errors
    }()

    private fun setupDevicePipe(input: FileInputStream) = {
        this.devicePipe = input.fd
        val device = StructPollfd()
        device.fd = input.fd
        device
    }()

    private fun setupPolls(errors: StructPollfd, device: StructPollfd) = {
        val polls = arrayOfNulls<StructPollfd>(2 + forwarder.size()) as Array<StructPollfd>
        polls[0] = errors
        polls[1] = device

        var i = 0
        while (i < forwarder.size()) {
            polls[2 + i] = forwarder[i].pipe
            i++
        }

        polls
    }()

    private fun poll(polls: Array<StructPollfd>) {
        while (true) {
            try {
                val result = Os.poll(polls, -1)
                if (result == 0) return
                if (polls[0].revents.toInt() != 0) {
                    log.w("Poll interrupted")
                    throw InterruptedException()
                }
                break
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                throw e
            }
        }
    }

    /**
     * 从 设备中读取 网络数据
     *
     * 我理解 inputStream 是 写入到 Vpn虚拟网络中的数据。 outputStream 是 Vpn虚拟网络 接收到的数据
     */
    private fun fromDeviceToProxy(device: StructPollfd, input: InputStream) {
        if (device.isEvent(OsConstants.POLLIN)) {
            try {
                // 读取 长度为1600 的数据  到 memory中
                val length = input.read(memory, 0, PACKET_BUFFER_SIZE)
                if (length > 0) {
                    fromDevice(memory, length)
                }
            } catch (ex: Exception) {
                // It's safe to ignore read errors if we are just stopping the thread
                if (!isInterrupted) throw ex
            }
        }
    }

    private fun fromOpenSocketsToProxy(polls: Array<StructPollfd>) {
        var index = 0
        val iterator = forwarder.iterator()
        while (iterator.hasNext()) {
            val rule = iterator.next()

            if (polls[2 + index++].isEvent(OsConstants.POLLIN)) {
                iterator.remove()
                try {
                    // 将
                    packet.setData(memory)
                    rule.socket.receive(packet)
                    toDevice(memory, packet.length, rule.originEnvelope)
                } catch (ex: Exception) {
                    log.w("Failed receiving socket".cause(ex))
                }

                try {
                    rule.socket.close()
                } catch (ex: Exception) {
                    log.w("Failed closing socket".cause(ex))
                }
            }
        }
    }

    private fun shouldInterruptLoop() = (isInterrupted || this.errorPipe == null)

    private fun cleanup() {
        log.v("Cleaning up resources: $this")
        forwarder.closeAll()

        try {
            Os.close(errorPipe)
        } catch (ex: Exception) {
        }
        errorPipe = null

        // This is managed by the SystemTunnel
        //try { Os.close(devicePipe) } catch (ex: Exception) {}
        //devicePipe = null
    }

    private var purgeCount = 0
    private fun purge() {
        if (++purgeCount % 1024 == 0) {
            try {
                val l = PacketFactoryPropertiesLoader.getInstance()
                val field = l.javaClass.getDeclaredField("loader")
                field.isAccessible = true
                val loader = field.get(l) as PropertiesLoader
                loader.clearCache()
            } catch (e: Exception) {
            }
        }
    }

}
