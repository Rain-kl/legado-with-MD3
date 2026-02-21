package io.legado.app.utils.moderation.config

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 文本审核引擎的配置类。
 *
 * 使用 Kotlin 默认参数代替 Java Builder 模式，所有参数均提供合理默认值。
 *
 * ```kotlin
 * val config = ModerationConfig(
 *     lineScoreThreshold = 3.0,
 *     chapterScoreThreshold = 5.0
 * )
 * ```
 *
 * @param lineScoreThreshold    单行得分阈值：行得分 >= 此值才纳入章节计分
 * @param chapterScoreThreshold 章节得分阈值：章节得分 >= 此值才标记为敏感章节
 * @param fallbackChunkSize     章节拆分失败时的回退分段行数
 * @param minChapterCount       章节拆分最少需识别的章节数，低于此值触发回退策略
 * @param fallbackMinCharacters 触发回退策略时的最低字符数
 * @param targetCharset         目标字符编码
 * @param summaryMaxLength      摘要最大截取字符数
 * @param adPatterns            广告过滤正则模式列表
 */
data class ModerationConfig(
    val lineScoreThreshold: Double = 2.0,
    val chapterScoreThreshold: Double = 3.5,
    val fallbackChunkSize: Int = 20,
    val minChapterCount: Int = 5,
    val fallbackMinCharacters: Long = 10_000,
    val targetCharset: Charset = StandardCharsets.UTF_8,
    val summaryMaxLength: Int = 200,
    val adPatterns: List<String> = DEFAULT_AD_PATTERNS
) {
    companion object {
        /** 默认广告过滤模式 */
        val DEFAULT_AD_PATTERNS: List<String> = listOf(
            "公众号",
            "作品来自互联网",
            "版权归作者",
            "-{10,}"
        )

        /** 使用全部默认值创建配置 */
        fun defaults(): ModerationConfig = ModerationConfig()
    }
}
