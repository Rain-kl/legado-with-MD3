package io.legado.app.utils.moderation.core

import io.legado.app.utils.moderation.config.ModerationConfig
import io.legado.app.utils.moderation.model.AnalysisResult
import io.legado.app.utils.moderation.model.ChapterAnalysis
import io.legado.app.utils.moderation.model.ModerationLevel
import io.legado.app.utils.moderation.pattern.ModerationPatterns
import java.util.LinkedHashMap
import kotlin.collections.iterator

/**
 * 内容分析器 —— 对已拆分的章节进行敏感内容评分。
 *
 * 评分规则：
 * - 每行按三个级别的正则模式匹配，加权计分
 * - 行得分 >= lineScoreThreshold 时纳入章节计分（halved）
 * - 章节得分 >= chapterScoreThreshold 时标记为敏感章节
 *
 * 线程安全：实例无可变状态，可在多线程间安全共享。
 */
class ContentAnalyzer(private val config: ModerationConfig) {

    companion object {
        private const val SUMMARY_PREFIX = "summary:"
    }

    /**
     * 分析章节字典，返回完整的分析结果。
     *
     * @param chapters 章节序号 → 行列表映射（来自 ChapterSplitter）
     * @return 不可变的分析结果
     */
    fun analyze(chapters: LinkedHashMap<Int, List<String>>): AnalysisResult {
        if (chapters.isEmpty()) {
            return emptyResult()
        }

        var totalScore = 0.0
        var flaggedChapters = 0
        var totalCharacters = 0L
        var summary = ""
        val details = mutableListOf<ChapterAnalysis>()

        for ((key, chapterLines) in chapters) {
            // 提取摘要
            if (key == 0 && chapterLines.isNotEmpty()) {
                val firstLine = chapterLines[0]
                if (firstLine.startsWith(SUMMARY_PREFIX)) {
                    summary = extractSummary(chapterLines)
                }
            }

            // 分析单章节
            val chapterResult = analyzeChapter(chapterLines)
            totalCharacters += chapterLines.sumOf { it.length.toLong() }
            totalScore += chapterResult.score

            if (chapterResult.isFlagged) {
                flaggedChapters++
                details.add(chapterResult)
            }
        }

        val totalChaps = chapters.size
        val flaggedRate = if (totalChaps > 0) flaggedChapters.toDouble() / totalChaps else 0.0
        totalScore = Math.round(totalScore * 100.0) / 100.0

        return AnalysisResult(
            totalScore = totalScore,
            flaggedChapters = flaggedChapters,
            totalChapters = totalChaps,
            flaggedRate = flaggedRate,
            totalCharacters = totalCharacters,
            summary = summary,
            details = details.toList()
        )
    }

    // ── 内部方法 ─────────────────────────────────────────────

    /** 分析单个章节 */
    private fun analyzeChapter(lines: List<String>): ChapterAnalysis {
        if (lines.isEmpty()) {
            return ChapterAnalysis(title = "", score = 0.0, flaggedLines = emptyList())
        }

        val title = lines[0]
        var chapterScore = 0.0
        val flaggedLines = mutableListOf<String>()

        for (line in lines) {
            val lineScore = scoreLine(line)
            if (lineScore >= config.lineScoreThreshold) {
                chapterScore += lineScore / 2.0
                flaggedLines.add(line)
            }
        }

        return ChapterAnalysis(
            title = title,
            score = chapterScore,
            flaggedLines = flaggedLines.toList()
        )
    }

    /**
     * 对单行内容进行敏感度评分。
     *
     * 先去除干扰字符，再依次匹配三个级别的正则并加权计分。
     * 使用 Set 去重同一级别的重复匹配项。
     *
     * @param line 原始行
     * @return 该行的敏感度得分
     */
    private fun scoreLine(line: String): Double {
        val cleaned = ModerationPatterns.stripNoise(line)
        var score = 0.0

        for (level in ModerationLevel.entries) {
            val pattern = ModerationPatterns.getPattern(level)
            val matcher = pattern.matcher(cleaned)
            val uniqueMatches = mutableSetOf<String>()

            while (matcher.find()) {
                uniqueMatches.add(matcher.group())
            }

            score += level.weight * uniqueMatches.size
        }

        return score
    }

    /** 从第 0 章节提取摘要文本 */
    private fun extractSummary(lines: List<String>): String {
        val raw = lines.joinToString("")
        val maxLen = config.summaryMaxLength
        return if (raw.length > maxLen) raw.substring(0, maxLen) else raw
    }

    /** 空结果 */
    private fun emptyResult(): AnalysisResult = AnalysisResult(
        totalScore = 0.0,
        flaggedChapters = 0,
        totalChapters = 0,
        flaggedRate = 0.0,
        totalCharacters = 0,
        summary = "",
        details = emptyList()
    )
}
