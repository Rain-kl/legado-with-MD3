package io.legado.app.utils.moderation.model

/**
 * 文本审核的最终分析结果（不可变数据类）。
 *
 * @param totalScore      总评分
 * @param flaggedChapters 被标记为敏感的章节数
 * @param totalChapters   总章节数
 * @param flaggedRate     敏感章节占比（0.0 ~ 1.0）
 * @param totalCharacters 全文总字符数
 * @param summary         文档摘要（前 N 字符）
 * @param details         各敏感章节的详细分析
 */
data class AnalysisResult(
    val totalScore: Double,
    val flaggedChapters: Int,
    val totalChapters: Int,
    val flaggedRate: Double,
    val totalCharacters: Long,
    val summary: String,
    val details: List<ChapterAnalysis>
) {
    /** 格式化的敏感占比百分比 */
    val flaggedRatePercent: String
        get() = "%.1f%%".format(flaggedRate * 100)

    /** 格式化的总字数（万字） */
    val totalCharactersFormatted: String
        get() = "%.2f万".format(totalCharacters / 10000.0)

    override fun toString(): String {
        val truncatedSummary = if (summary.length > 50) summary.substring(0, 50) + "..." else summary
        return "AnalysisResult(" +
                "totalScore=$totalScore, " +
                "flaggedChapters=$flaggedChapters, " +
                "totalChapters=$totalChapters, " +
                "flaggedRate=$flaggedRatePercent, " +
                "totalCharacters=$totalCharactersFormatted, " +
                "summary='$truncatedSummary', " +
                "detailCount=${details.size})"
    }
}
