package io.legado.app.utils.moderation.core

import io.legado.app.utils.EncodingDetect
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * 文件编码检测与转换工具。
 *
 * 基于项目内置的 [EncodingDetect] 进行编码探测，支持将任意编码的文本文件
 * 读取为 UTF-8 行列表。
 *
 * 设计理念：
 * - 不修改原文件 —— 所有转换在内存中完成
 * - 对未知编码进行优雅降级（默认回退到 UTF-8）
 * - 读取时自动过滤空行和 BOM 标记
 */
object TextFileReader {

    /** BOM 字节标记 */
    private const val BOM = "\uFEFF"

    /**
     * 探测指定文件的字符编码。
     *
     * @param file 文件
     * @return 检测到的 Charset；无法识别时返回 UTF-8
     */
    fun detectEncoding(file: File): Charset {
        val charsetName = EncodingDetect.getEncode(file)
        return try {
            Charset.forName(charsetName)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    /**
     * 以自动检测编码的方式读取文件全部行。
     *
     * 自动处理：
     * - 编码检测与转换
     * - BOM 去除
     * - 全角/半角空格、换行符的规范化
     * - 过滤纯空白行
     *
     * @param file 文件
     * @return 规范化后的非空行列表
     */
    fun readLines(file: File): List<String> {
        val charset = detectEncoding(file)
        return readLinesWithCharset(file, charset)
    }

    /**
     * 以指定编码读取文件全部行，执行标准规范化。
     */
    fun readLinesWithCharset(file: File, charset: Charset): List<String> {
        BufferedReader(InputStreamReader(file.inputStream(), charset)).use { reader ->
            return reader.lineSequence()
                .map { normalizeLine(it) }
                .filter { it.isNotEmpty() }
                .toList()
        }
    }

    /**
     * 从字符串内容直接构建行列表（用于已在内存中的文本）。
     *
     * @param content 原始文本内容
     * @return 规范化后的非空行列表
     */
    fun readLinesFromString(content: String): List<String> =
        content.lineSequence()
            .map { normalizeLine(it) }
            .filter { it.isNotEmpty() }
            .toList()

    // ── 内部工具 ─────────────────────────────────────────────

    /** 行规范化：去除 BOM、全角空格、首尾空白 */
    private fun normalizeLine(line: String): String {
        var result = line
        if (result.startsWith(BOM)) {
            result = result.substring(1)
        }
        return result
            .replace("\u3000", "")  // 全角空格
            .replace("\r", "")       // 回车符
            .trim()                  // 首尾空白
    }
}
