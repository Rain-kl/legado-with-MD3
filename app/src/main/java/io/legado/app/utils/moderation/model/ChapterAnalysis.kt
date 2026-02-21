package io.legado.app.utils.moderation.model

/**
 * 单个章节的审核分析结果（不可变数据类）。
 *
 * @param title        章节标题
 * @param score        章节综合得分
 * @param flaggedLines 被标记的敏感行内容
 */
data class ChapterAnalysis(
    val title: String,
    val score: Double,
    val flaggedLines: List<String>
) {
    /** 该章节是否被判定为敏感章节 */
    val isFlagged: Boolean get() = score >= 3.5
}
