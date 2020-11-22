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

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import model.BlokadaException
import model.SystemTunnelRevoked
import service.ContextService
import ui.TunnelViewModel
import ui.app
import ui.utils.cause
import utils.Logger
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 实际 建立Vpn的Service
 *
 * 在正式建立 Vpn之前，需要配置 以下参数：
 * 1）MTU（Maximun Transmission Unit）：即表示 虚拟网络端口的最大传输单元。如果发送的包的长度 大于这个数字，则会被 分包。
 * 2）Address：即这个 虚拟网络端口的IP地址
 * 3）Route：只有匹配上的IP包，才会被路由到 虚拟端口上去。如果是 0.0.0.0/0的话，则会将 所有的IP包 都路由到 虚拟端口上去。
 * 4）DNS Server：就是该端口的DNS服务器地址；
 * 5）Search Domain：就是添加DNS域名的自动补齐。DNS服务器必须通过全域名进行搜索，但每次查找都输入全域名太麻烦了，可以通过配置域名的自动补齐规则予以简化；
 * 6）Session：就是你要建立的VPN连接的名字，它将会在系统管理的与VPN连接相关的通知栏和对话框中显示出来；
 * 7）Configure Intent，这个intent指向一个配置页面，用来配置VPN链接。它不是必须的，如果没设置的话，则系统弹出的VPN相关对话框中不会出现配置按钮。
 *
 * 最后 调用 Builder.establish函数，如果一切正常的话，tun0虚拟网络接口就建立完成了。并且，同时还会通过iptables命令，修改NAT表，将所有数据转发到tun0接口上。
 *
 * 这之后，就可以通过读写VpnService.Builder返回的ParcelFileDescriptor实例来获得设备上所有向外发送的IP数据包和返回处理过后的IP数据包到TCP/IP协议栈：
 *
 * ParcelFileDescriptor类有一个getFileDescriptor函数，其会返回一个文件描述符，这样就可以将对接口的读写操作转换成对文件的读写操作。
 *
 * 每次调用FileInputStream.read函数会读取一个IP数据包，而调用FileOutputStream.write函数会写入一个IP数据包到TCP/IP协议栈。
 *
 * 一般的应用程序，在获得这些IP数据包后，会将它们再通过socket发送出去。但是，这样做会有问题，你的程序建立的socket和别的程序建立的socket其实没有区别，
 * 发送出去后，还是会被转发到tun0接口，再回到你的程序，这样就是一个死循环了。
 *
 * 为了解决这个问题，VpnService类提供了一个叫protect的函数，在VPN程序自己建立socket之后，必须要对其进行保护：protect(my_socket);
 *
 * 其背后的原理是将这个socket和真实的网络接口进行绑定，保证通过这个socket发送出去的数据包一定是通过真实的网络接口发送出去的，不会被转发到虚拟的tun0接口上去。
 *
 * 参考：http://www.zyiz.net/tech/detail-57627.html
 *
 */

class SystemTunnel : VpnService() {

    private val log = Logger("SystemTunnel")

    // 绑定 Service时 onBind 方法返回的Binder
    private var binder: SystemTunnelBinder? = null
        @Synchronized get
        @Synchronized set

    private var config: SystemTunnelConfig? = null
        @Synchronized get
        @Synchronized set

    private var reactedToStart = false
        @Synchronized get
        @Synchronized set

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = {
        ContextService.setContext(this)
        log.v("onStartCommand received: $this, intent: $intent")

        if (!reactedToStart) {
            // 此处的目的是 为了 初始化 TunnelViewModel,只有第一次 创建 才需要
            // System calls us twice in a row on boot
            reactedToStart = true

            // 开启 系统 Vpn Service时，顺便 打开 tunnel
            // This might be a misuse
            val tunnelVM = ViewModelProvider(app()).get(TunnelViewModel::class.java)
            tunnelVM.turnOnWhenStartedBySystem()
        }

        START_STICKY
    }()

    override fun onBind(intent: Intent?): IBinder? {
        if (SYSTEM_TUNNEL_BINDER_ACTION == intent?.action) {
            log.v("onBind received: $this")

            // Service 绑定成功，返回 SystemTunnelBinder对象
            binder = SystemTunnelBinder(this)
            return binder
        }
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (SYSTEM_TUNNEL_BINDER_ACTION == intent?.action) {
            log.v("onUnbind received: $this")
            return true
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        log.v("onDestroy received: $this")
        turnOff()
        binder { it.onTunnelClosed(null) }
        super.onDestroy()
    }

    override fun onRevoke() {
        log.w("onRevoke received: $this")
        turnOff()
        binder { it.onTunnelClosed(SystemTunnelRevoked()) }
        super.onRevoke()
    }

    fun queryConfig() = config

    // 打开 Vpn连接
    fun turnOn(): SystemTunnelConfig {

        log.v("Tunnel turnOn() called")
        val tunnelBuilder = super.Builder()
        // 开启 vpn连接前，配置 VPN
        binder { it.onConfigureTunnel(tunnelBuilder) }

        log.v("Asking system for tunnel")
        // 通过 builder 打开 Vpn连接
        val descriptor = tunnelBuilder.establish() ?: throw BlokadaException("Tunnel establish() returned no fd")

        val fd = descriptor.fileDescriptor
        val config = SystemTunnelConfig(descriptor, FileInputStream(fd), FileOutputStream(fd))
        this.config = config
        return config
    }

    fun turnOff() {
        log.v("Tunnel turnOff() called")
        config?.let { config ->
            log.v("Closing tunnel descriptors")
            try {
                config.fd.close()
                config.deviceIn.close()
                config.deviceOut.close()
            } catch (ex: Exception) {
                log.w("Could not close SystemTunnel descriptor".cause(ex))
            }
        }

        config = null
    }

    private fun binder(exec: (SystemTunnelBinder) -> Unit) {
        binder?.let(exec) ?: log.e("No binder attached: $this")
    }

}

// 当 vpn 连接成功后，生成的相关信息
class SystemTunnelConfig(
    val fd: ParcelFileDescriptor,

    //
    val deviceIn: FileInputStream,
    val deviceOut: FileOutputStream
)

// Vpn Service 的 Binder对象，在 绑定Service时使用
// 当 Service 绑定成功后，onBind方法 返回的 对象
class SystemTunnelBinder(
    // 返回 Vpn Service 对象
    val tunnel: SystemTunnel,

    // Vpn Service 销毁时的 callback
    var onTunnelClosed: (exception: BlokadaException?) -> Unit = {},

    // 开启Vpn连接前， 配置 vpn 的 callback
    var onConfigureTunnel: (vpn: VpnService.Builder) -> Unit = {}
) : Binder()

const val SYSTEM_TUNNEL_BINDER_ACTION = "SystemTunnel"
