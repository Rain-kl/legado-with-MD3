package io.legado.app.cust.webdav

import io.legado.app.help.AppWebDav
import io.legado.app.lib.webdav.WebDav
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

object CustWebDavBookLocator {

    private const val MAX_SCAN_DEPTH = 6
    private const val MAX_SCAN_DIR_COUNT = 200
    private val filenamePathCache = ConcurrentHashMap<String, String>()

    suspend fun findBookPathByName(fileName: String): String? {
        val target = fileName.trim()
        if (target.isBlank()) return null
        filenamePathCache[target]?.let { return it }
        val defaultBookWebDav = AppWebDav.defaultBookWebDav ?: return null
        val queue = ArrayDeque<Pair<String, Int>>().apply {
            add(defaultBookWebDav.rootBookUrl to 0)
        }
        var scannedDirCount = 0
        while (queue.isNotEmpty() && scannedDirCount < MAX_SCAN_DIR_COUNT) {
            val (dirPath, depth) = queue.removeFirst()
            scannedDirCount++
            val files = kotlin.runCatching {
                WebDav(dirPath, defaultBookWebDav.authorization).listFiles()
            }.getOrNull() ?: continue
            files.firstOrNull { !it.isDir && it.displayName.equals(target, true) }?.let {
                filenamePathCache[target] = it.path
                return it.path
            }
            if (depth >= MAX_SCAN_DEPTH) continue
            files.asSequence().filter { it.isDir }.forEach {
                queue.addLast(it.path to (depth + 1))
            }
        }
        return null
    }
}
