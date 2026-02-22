package io.legado.app.utils.moderation.core

import io.legado.app.utils.moderation.config.ModerationConfig
import io.legado.app.utils.moderation.model.AnalysisResult
import io.legado.app.utils.moderation.model.ChapterAnalysis
import io.legado.app.utils.moderation.model.ModerationLevel
import io.legado.app.utils.moderation.pattern.ModerationPatterns
import java.util.EnumMap
import java.util.LinkedHashMap
import java.util.stream.Collectors
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class ChapterQuickScore(
    val score: Double,
    val flaggedLinesCount: Int,
    val isFlagged: Boolean
)

private data class ScanStats(
    val cleanedLength: Long,
    val levelPhraseHits: Map<ModerationLevel, MutableMap<String, Int>>,
    val levelTotalHits: IntArray
)

private data class ScoreMetrics(
    val baseRawScore: Double,
    val diversityFactor: Double,
    val rawScore: Double,
    val density: Double,
    val finalScore: Double,
    val totalMatches: Int
)

private data class FlaggedParagraph(
    val level: ModerationLevel,
    val lineIndex: Int,
    val text: String
)

private data class ChapterComputation(
    val key: Int,
    val chapterChars: Long,
    val scan: ScanStats,
    val chapterScore: Double,
    val detail: ChapterAnalysis?
)

/**
 * 内容分析器 —— 对已拆分的章节进行敏感内容评分。
 *
 * 评分规则（密度模型）：
 * - 按四个级别正则统计命中次数，按 1/3/7/15 非对称加权得出基础分
 * - 上下文门控：L2 需满足 L0+L1 最低命中；L3 需满足 L0+L1+L2 最低命中
 * - 同一短语重复命中使用几何衰减；不同短语数量触发多样性增益
 * - 进行长度归一化：density = rawScore * (1000 / textLength)
 * - 非线性压缩：finalScore = min(100, 6.5 * sqrt(density))
 * - 总评分 = 所有敏感章节分数总和 * 系数 y(x)，其中 x=敏感章节占比
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

        val summary = extractSummaryFromChapters(chapters)
        val computations = computeChapters(chapters)

        var totalCharacters = 0L
        var flaggedChapters = 0
        var flaggedChaptersScoreSum = 0.0
        val details = mutableListOf<ChapterAnalysis>()

        for (computation in computations.sortedBy { it.key }) {
            totalCharacters += computation.chapterChars
            if (computation.detail != null) {
                flaggedChapters++
                flaggedChaptersScoreSum += computation.chapterScore
                details.add(computation.detail)
            }
        }

        val totalChaps = chapters.size
        val flaggedRate = if (totalChaps > 0) flaggedChapters.toDouble() / totalChaps else 0.0
        val multiplier = scoreMultiplierByFlaggedRate(flaggedRate)
        val totalScore = round2(flaggedChaptersScoreSum * multiplier)

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

    /**
     * 轻量章节评分接口：仅返回分数与命中行数，不构建明细列表。
     * 用于性能敏感场景（例如目录页快速审查）。
     */
    fun analyzeChapterQuick(lines: List<String>): ChapterQuickScore {
        if (lines.isEmpty()) {
            return ChapterQuickScore(
                score = 0.0,
                flaggedLinesCount = 0,
                isFlagged = false
            )
        }

        val chapterText = buildString {
            for (line in lines) append(line)
        }
        val metrics = scoreFromScan(scanText(chapterText))
        val chapterScore = round2(metrics.finalScore)
        return ChapterQuickScore(
            score = chapterScore,
            flaggedLinesCount = metrics.totalMatches,
            isFlagged = chapterScore >= config.chapterScoreThreshold
        )
    }

    /**
     * 扫描文本，得到各级别短语命中统计。
     *
     * 该步骤仅负责“找出命中项”，不做打分。
     */
    private fun scanText(text: String): ScanStats {
        val cleaned = ModerationPatterns.stripNoise(text)
        if (cleaned.isBlank()) {
            return emptyScanStats()
        }

        val levelPhraseHits = EnumMap<ModerationLevel, MutableMap<String, Int>>(
            ModerationLevel::class.java
        )
        val levelTotalHits = IntArray(ModerationLevel.entries.size)

        for (level in ModerationLevel.entries) {
            val matcher = ModerationPatterns.getPattern(level).matcher(cleaned)
            val phraseCounter = mutableMapOf<String, Int>()
            while (matcher.find()) {
                val phrase = matcher.group()
                phraseCounter[phrase] = (phraseCounter[phrase] ?: 0) + 1
            }
            levelPhraseHits[level] = phraseCounter
            levelTotalHits[level.ordinal] = phraseCounter.values.sum()
        }

        return ScanStats(
            cleanedLength = cleaned.length.toLong(),
            levelPhraseHits = levelPhraseHits,
            levelTotalHits = levelTotalHits
        )
    }

    /**
     * 从扫描结果计算风险分数。
     *
     */
    private fun scoreFromScan(scan: ScanStats): ScoreMetrics {
        if (scan.cleanedLength <= 0L) {
            return ScoreMetrics(
                baseRawScore = 0.0,
                diversityFactor = 1.0,
                rawScore = 0.0,
                density = 0.0,
                finalScore = 0.0,
                totalMatches = 0
            )
        }

        var baseRawScore = 0.0
        var totalMatches = 0
        var weightedUniquePhrases = 0

        val l0Hits = scan.levelTotalHits[ModerationLevel.MILD.ordinal]
        val l1Hits = scan.levelTotalHits[ModerationLevel.MODERATE.ordinal]
        val l2Hits = scan.levelTotalHits[ModerationLevel.SEVERE.ordinal]
        val l2Enabled = l0Hits + l1Hits >= config.minL0L1SupportForL2
        val l3Enabled = l0Hits + l1Hits + l2Hits >= config.minL0ToL2SupportForL3

        for (level in ModerationLevel.entries) {
            val enabled = when (level) {
                ModerationLevel.SEVERE -> l2Enabled
                ModerationLevel.CRITICAL -> l3Enabled
                else -> true
            }
            if (!enabled) continue

            val phraseCounter = scan.levelPhraseHits[level] ?: continue
            if (phraseCounter.isEmpty()) continue
            weightedUniquePhrases += phraseCounter.size * level.weight
            for ((_, count) in phraseCounter) {
                totalMatches += count
                baseRawScore += level.weight * decayedCount(count)
            }
        }

        val diversityFactor = diversityFactor(weightedUniquePhrases)
        val rawScore = baseRawScore * diversityFactor
        val density = rawScore * (config.densityBaseChars / scan.cleanedLength.coerceAtLeast(1L).toDouble())
        val finalScore = min(config.maxScore, config.compressionScale * sqrt(density.coerceAtLeast(0.0)))

        return ScoreMetrics(
            baseRawScore = baseRawScore,
            diversityFactor = diversityFactor,
            rawScore = rawScore,
            density = density,
            finalScore = finalScore,
            totalMatches = totalMatches
        )
    }

    private fun buildChapterAnalysis(
        lines: List<String>,
        score: Double,
        chapterScan: ScanStats
    ): ChapterAnalysis {
        val title = lines.firstOrNull().orEmpty()
        val flaggedParagraphs = collectFlaggedParagraphs(
            lines = lines,
            chapterScan = chapterScan,
            limit = config.explainTopPhrasesLimit
        )
        return ChapterAnalysis(
            title = title,
            score = score,
            flaggedLines = flaggedParagraphs,
            flaggedThreshold = config.chapterScoreThreshold
        )
    }

    private fun computeChapters(chapters: LinkedHashMap<Int, List<String>>): List<ChapterComputation> {
        val entries = chapters.entries.toList()
        val shouldParallel =
            config.parallelChapterAnalysis &&
                    entries.size >= config.parallelChapterMinCount &&
                    Runtime.getRuntime().availableProcessors() > 1

        return if (shouldParallel) {
            entries.parallelStream()
                .map { computeSingleChapter(it.key, it.value) }
                .collect(Collectors.toList())
        } else {
            entries.map { computeSingleChapter(it.key, it.value) }
        }
    }

    private fun computeSingleChapter(key: Int, lines: List<String>): ChapterComputation {
        var chapterChars = 0L
        val chapterText = buildString {
            for (line in lines) {
                append(line)
                chapterChars += line.length
            }
        }

        val scan = scanText(chapterText)
        val chapterScore = round2(scoreFromScan(scan).finalScore)
        val detail = if (chapterScore >= config.chapterScoreThreshold) {
            buildChapterAnalysis(lines = lines, score = chapterScore, chapterScan = scan)
        } else {
            null
        }

        return ChapterComputation(
            key = key,
            chapterChars = chapterChars,
            scan = scan,
            chapterScore = chapterScore,
            detail = detail
        )
    }

    private fun extractSummaryFromChapters(chapters: LinkedHashMap<Int, List<String>>): String {
        val chapter0 = chapters[0] ?: return ""
        val firstLine = chapter0.firstOrNull() ?: return ""
        return if (firstLine.startsWith(SUMMARY_PREFIX)) extractSummary(chapter0) else ""
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

    private fun emptyScanStats(): ScanStats =
        ScanStats(
            cleanedLength = 0L,
            levelPhraseHits = EnumMap<ModerationLevel, MutableMap<String, Int>>(
                ModerationLevel::class.java
            ).apply {
                for (level in ModerationLevel.entries) {
                    put(level, mutableMapOf())
                }
            },
            levelTotalHits = IntArray(ModerationLevel.entries.size)
        )

    private fun round2(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

    private fun decayedCount(count: Int): Double {
        if (count <= 0) return 0.0
        val decay = config.repetitionDecay.coerceIn(0.0, 1.0)
        if (decay == 1.0) return count.toDouble()
        // 几何衰减：1 + d + d^2 + ... + d^(n-1)
        return (1.0 - decay.pow(count.toDouble())) / (1.0 - decay)
    }

    private fun diversityFactor(weightedUniquePhrases: Int): Double {
        if (weightedUniquePhrases <= 0) return 1.0
        val boost = 1.0 + config.diversityBoostCoeff * ln(1.0 + weightedUniquePhrases.toDouble())
        return boost.coerceIn(1.0, config.maxDiversityBoostFactor)
    }

    /**
     * 总评分系数 y(x)，x 为敏感章节占比：
     * - [0,0.1]      : 0.6
     * - (0.1,0.6]    : 0.6 + 0.3 * log_a(1 + (a-1)t), t=(x-0.1)/0.5
     * - (0.6,1]      : 0.9 + 0.35 * u^b, u=(x-0.6)/0.4
     */
    private fun scoreMultiplierByFlaggedRate(flaggedRate: Double): Double {
        val x = flaggedRate.coerceIn(0.0, 1.0)
        val a = config.scoreMultiplierLogBaseA.coerceAtLeast(1.000001)
        val b = config.scoreMultiplierPowB.coerceAtLeast(1.000001)

        return when {
            x <= 0.1 -> 0.6
            x <= 0.6 -> {
                val t = (x - 0.1) / 0.5
                val value = 1.0 + (a - 1.0) * t
                val logA = ln(value) / ln(a)
                0.6 + 0.3 * logA
            }
            else -> {
                val u = (x - 0.6) / 0.4
                0.9 + 0.35 * u.pow(b)
            }
        }
    }

    private fun collectFlaggedParagraphs(
        lines: List<String>,
        chapterScan: ScanStats,
        limit: Int
    ): List<String> {
        if (lines.isEmpty()) return emptyList()

        val l0Hits = chapterScan.levelTotalHits[ModerationLevel.MILD.ordinal]
        val l1Hits = chapterScan.levelTotalHits[ModerationLevel.MODERATE.ordinal]
        val l2Hits = chapterScan.levelTotalHits[ModerationLevel.SEVERE.ordinal]
        val l2Enabled = l0Hits + l1Hits >= config.minL0L1SupportForL2
        val l3Enabled = l0Hits + l1Hits + l2Hits >= config.minL0ToL2SupportForL3

        val flagged = mutableListOf<FlaggedParagraph>()
        for ((lineIndex, line) in lines.withIndex()) {
            val cleanedLine = ModerationPatterns.stripNoise(line)
            if (cleanedLine.isBlank()) continue

            val highestLevel = ModerationLevel.entries
                .asReversed()
                .firstOrNull { level ->
                    val enabled = when (level) {
                        ModerationLevel.SEVERE -> l2Enabled
                        ModerationLevel.CRITICAL -> l3Enabled
                        else -> true
                    }
                    enabled && ModerationPatterns.getPattern(level).matcher(cleanedLine).find()
                } ?: continue

            flagged.add(
                FlaggedParagraph(
                    level = highestLevel,
                    lineIndex = lineIndex,
                    text = line
                )
            )
        }

        val sorted = flagged.sortedWith(
            compareByDescending<FlaggedParagraph> { it.level.ordinal }
                .thenBy { it.lineIndex }
        )
        val limited = if (limit > 0) sorted.take(limit) else sorted
        return limited.map { it.text }
    }
}
