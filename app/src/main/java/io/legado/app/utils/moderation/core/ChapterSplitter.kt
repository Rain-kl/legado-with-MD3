package io.legado.app.utils.moderation.core

import io.legado.app.utils.moderation.config.ModerationConfig
import io.legado.app.utils.moderation.pattern.ChapterPatterns
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import java.util.regex.Pattern

/**
 * 章节拆分器。
 *
 * 将文本文件按章节标题正则拆分为有序的章节映射。
 *
 * 拆分策略：
 * 1. 首先用全部主正则 + 番外正则做预扫描，统计每条正则的匹配次数
 * 2. 选出匹配频次最高的主正则作为最佳拆分依据
 * 3. 仅用最佳主正则 + 番外正则做最终拆分
 * 4. 若拆分后章节数不足且文本较长，则回退到按固定行数等分
 *
 * 返回的 Map 键为章节序号（从 0 开始），值为该章节的行列表。
 * 第 0 章节若非显式标题行，将被标记为 "summary:" 前缀。
 */
class ChapterSplitter(private val config: ModerationConfig) {

    private val adFilter = AdFilter(config)

    companion object {
        private const val SUMMARY_PREFIX = "summary: "
    }

    // ── 公共 API ─────────────────────────────────────────────

    /**
     * 从文件拆分章节。
     *
     * @param file 文件
     * @return 章节序号 → 行列表的有序映射
     * @throws IOException 文件读取失败
     */
    @Throws(IOException::class)
    fun split(file: File): LinkedHashMap<Int, List<String>> {
        val lines = TextFileReader.readLines(file)
        return splitFromLines(lines)
    }

    /**
     * 从已有的行列表拆分章节。
     *
     * @param rawLines 原始行列表
     * @return 章节序号 → 行列表的有序映射
     */
    fun splitFromLines(rawLines: List<String>): LinkedHashMap<Int, List<String>> {
        val lines = adFilter.filter(rawLines)

        if (lines.isEmpty()) {
            return LinkedHashMap()
        }

        // 阶段1：预扫描，确定最佳拆分正则
        val bestMainIndex = detectBestMainPattern(lines)

        // 阶段2：用最佳正则做拆分
        val splitPatterns = if (bestMainIndex >= 0) {
            ChapterPatterns.getSplitPatterns(bestMainIndex)
        } else {
            ChapterPatterns.getAllSplitPatterns()
        }

        var chapters = doSplit(lines, splitPatterns)

        // 阶段3：回退检查
        val totalChars = lines.sumOf { it.length.toLong() }
        if (chapters.size < config.minChapterCount
            && totalChars > config.fallbackMinCharacters
        ) {
            chapters = fallbackSplit(lines)
        }

        return chapters
    }

    // ── 内部逻辑 ─────────────────────────────────────────────

    /**
     * 预扫描：统计各主正则的匹配次数，选出匹配最多的那条。
     *
     * @return 最佳主正则的索引；无匹配返回 -1
     */
    private fun detectBestMainPattern(lines: List<String>): Int {
        val mainPatterns = ChapterPatterns.mainPatterns
        val excludePatterns = ChapterPatterns.excludePatterns
        val counts = IntArray(mainPatterns.size)

        for (line in lines) {
            if (matchesAny(line, excludePatterns)) continue
            for (i in mainPatterns.indices) {
                if (mainPatterns[i].matcher(line).matches()) {
                    counts[i]++
                }
            }
        }

        var bestIndex = -1
        var maxCount = 0
        for (i in counts.indices) {
            if (counts[i] > maxCount) {
                maxCount = counts[i]
                bestIndex = i
            }
        }
        return bestIndex
    }

    /**
     * 使用指定的正则列表拆分行列表为章节。
     */
    private fun doSplit(
        lines: List<String>,
        patterns: List<Pattern>
    ): LinkedHashMap<Int, List<String>> {
        val chapters = LinkedHashMap<Int, MutableList<String>>()
        var chapterIndex = 0

        for (line in lines) {
            if (matchesAny(line, patterns)) {
                chapterIndex++
            }
            chapters.getOrPut(chapterIndex) { mutableListOf() }.add(line)
        }

        // 标记第 0 章节为摘要
        markSummary(chapters)

        // 转为不可变 List
        return LinkedHashMap<Int, List<String>>().apply {
            chapters.forEach { (k, v) -> put(k, v.toList()) }
        }
    }

    /**
     * 回退策略：按固定行数等分。
     */
    private fun fallbackSplit(lines: List<String>): LinkedHashMap<Int, List<String>> {
        val chapters = LinkedHashMap<Int, MutableList<String>>()
        val chunkSize = config.fallbackChunkSize

        for (i in lines.indices) {
            val chapterIndex = i / chunkSize
            chapters.getOrPut(chapterIndex) { mutableListOf() }.add(lines[i])
        }

        return LinkedHashMap<Int, List<String>>().apply {
            chapters.forEach { (k, v) -> put(k, v.toList()) }
        }
    }

    /**
     * 如果第 0 章节的第一行不是标题，加上 "summary: " 前缀。
     */
    private fun markSummary(chapters: LinkedHashMap<Int, MutableList<String>>) {
        val firstChapter = chapters[0] ?: return
        if (firstChapter.isNotEmpty()) {
            val firstLine = firstChapter[0]
            if (!firstLine.startsWith(SUMMARY_PREFIX)) {
                firstChapter[0] = SUMMARY_PREFIX + firstLine
            }
        }
    }

    /**
     * 判断行是否匹配任一正则。
     */
    private fun matchesAny(line: String, patterns: List<Pattern>): Boolean =
        patterns.any { it.matcher(line).matches() }
}
