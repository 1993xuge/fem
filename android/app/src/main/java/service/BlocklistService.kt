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

import android.util.Base64.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import model.BlokadaException
import model.Uri
import utils.Logger

/**
 * 黑名单
 */
object BlocklistService {

    private const val DEFAULT_BLOCKLIST = "default_blocklist.zip"
    private const val MERGED_BLOCKLIST = "merged_blocklist"
    const val USER_ALLOWED = "allowed"
    const val USER_DENIED = "denied"

    private val log = Logger("Blocklist")
    private val http = HttpService
    private val fileService = FileService
    private val context = ContextService

    suspend fun setup() {
        // 从文件目录加载 merged_blocklist 文件
        val destination = fileService.commonDir().file(MERGED_BLOCKLIST)
        log.w("setup: destination = $destination")

        if (!fileService.exists(destination)) {
            // merged_blocklist 文件不存在
            log.w("Initiating default blocklist files")

            // 加载 用户主动允许的列表文件，并将其清空
            val allowed = fileService.commonDir().file(USER_ALLOWED)
            fileService.save(allowed, "")
            log.w("setup: allowed = $allowed")

            // 加载 用户主动禁止的列表文件，并将其清空
            val denied = fileService.commonDir().file(USER_DENIED)
            fileService.save(denied, "")
            log.w("setup: denied = $denied")

            // 新建 default_blocklist.zip 文件
            val default = fileService.commonDir().file(DEFAULT_BLOCKLIST)
            log.w("setup: default = $default")
            // 读取 asset目录下的 default_blocklist.zip 文件，将其写入到 应用目录的default_blocklist.zip文件中
            val asset = context.requireAppContext().assets.open(DEFAULT_BLOCKLIST)
            val decodedAsset = ZipService.decodeStream(asset, key = DEFAULT_BLOCKLIST)
            fileService.save(source = decodedAsset, destination = default)


            // 将 default_blocklist.zip 中的内容 merge 到 merged_blocklist
            fileService.merge(listOf(default), destination)

            // 对 destination 中的内容进行 去重、排序等操作
            sanitize(destination)
        }
    }

    // 下载 urls 列表中的内容
    suspend fun downloadAll(urls: List<Uri>) {
        log.v("Starting download of ${urls.size} urls")
        coroutineScope {
            for (url in urls) {
                log.v("downloadAll: url = $url")
                if (!hasDownloaded(url))
                    launch(Dispatchers.IO) {
                        download(url)
                        sanitize(getDestination(url))
                    }
            }
        }
        log.v("Done downloading")
    }

    // 将
    suspend fun mergeAll(urls: List<Uri>) {
        log.v("Merging ${urls.size} blocklists")
        var destinations = urls.map { getDestination(it) }

        if (destinations.isEmpty()) {
            log.w("No selected blocklists, using default")
            // 如果 destinations 是空，则 读取 默认的禁止列表
            destinations += fileService.commonDir().file(DEFAULT_BLOCKLIST)
        }

        // 用户主动 禁止的列表
        val userDenied = fileService.commonDir().file(USER_DENIED)
        if (fileService.exists(userDenied)) {
            // Always include user blocklist (whitelist is included using engine api)
            log.v("Including user denied list")
            // 用户主动禁止的文件存在，也将其 加入到 destinations
            destinations += userDenied
        }

        // 加载 merged_blocklist 文件
        val merged = fileService.commonDir().file(MERGED_BLOCKLIST)

        // 将 destinations 中的内容 都 写入到 merged_blocklist中
        fileService.merge(destinations, merged)
        sanitize(merged)

        log.v("Done merging")
    }

    fun removeAll(urls: List<Uri>) {
        log.v("Removing ${urls.size} blocklists")
        for (url in urls) {
            remove(getDestination(url))
        }
        log.v("Done removing")
    }

    // 加载 merged_blocklist 文件的内容。这个是 全部的block 列表
    fun loadMerged(): List<String> {
        return fileService.load(fileService.commonDir().file(MERGED_BLOCKLIST))
    }

    // 加载 用户 主动 允许的列表
    fun loadUserAllowed(): List<String> {
        return fileService.load(fileService.commonDir().file(USER_ALLOWED))
    }

    // 加载 用户主动禁止的列表
    fun loadUserDenied(): List<String> {
        return fileService.load(fileService.commonDir().file(USER_DENIED))
    }

    // 读取 list 中的内容
    // 将其排序， 并去重
    private fun sanitize(list: Uri) {
        log.v("Sanitizing list: $list")
        val content = fileService.load(list).sorted().distinct().toMutableList()
        var i = content.count()
        while (--i >= 0) {
            if (!isLineOk(content[i])) {
                content.removeAt(i)
            } else {
                content[i] = removeIp(content[i]).trim()
            }
        }
        fileService.save(list, content)
        log.v("Sanitizing done, left ${content.size} lines")
    }

    private fun isLineOk(line: String) = when {
        line.startsWith("#") -> false
        line.trim().isEmpty() -> false
        else -> true
    }

    private fun removeIp(line: String) = when {
        line.startsWith("127.0.0.1") -> line.removePrefix("127.0.0.1")
        line.startsWith("0.0.0.0") -> line.removePrefix("0.0.0.0")
        else -> line
    }

    private fun hasDownloaded(url: Uri): Boolean {
        return fileService.exists(getDestination(url))
    }

    private fun remove(url: Uri) {
        log.v("Removing blocklist file for: $url")
        fileService.remove(getDestination(url))
    }

    private suspend fun download(url: Uri) {
        log.v("Downloading blocklist: $url")
        val destination = getDestination(url)
        try {
            val content = http.makeRequest(url)
            fileService.save(destination, content)
        } catch (ex: Exception) {
            remove(url)
            throw BlokadaException("Could not fetch domains for: $url")
        }
    }

    private fun getDestination(url: Uri): Uri {
        val filename = encodeToString(url.toByteArray(), NO_WRAP)
        return fileService.commonDir().file(filename)
    }

}