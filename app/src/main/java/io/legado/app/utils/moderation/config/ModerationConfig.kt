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
 *     chapterScoreThreshold = 18.0
 * )
 * ```
 *
 * @param lineScoreThreshold    兼容旧版字段（新密度算法不再使用）
 * @param chapterScoreThreshold 章节风险分阈值：章节分 >= 此值标记为敏感章节
 * @param densityBaseChars      密度归一化基准字数
 * @param compressionScale      非线性压缩系数（sqrt 前的乘子）
 * @param maxScore              输出分数上限
 * @param repetitionDecay       同一短语重复命中的衰减系数（0~1，越小衰减越强）
 * @param diversityBoostCoeff   短语多样性增益系数（越大越鼓励“不同词”共同出现）
 * @param maxDiversityBoostFactor 多样性增益上限，防止分数失控
 * @param scoreMultiplierLogBaseA 总评分系数函数中的对数底 a（需 > 1）
 * @param scoreMultiplierPowB   总评分系数函数中的幂指数 b（需 > 1）
 * @param explainTopPhrasesLimit 解释信息中最多保留的命中段落数量（按 L3→L0 排序）
 * @param minL0L1SupportForL2    L2 生效所需的最低 L0+L1 命中数
 * @param minL0ToL2SupportForL3  L3 生效所需的最低 L0+L1+L2 命中数
 * @param parallelChapterAnalysis 是否启用章节级并行分析
 * @param parallelChapterMinCount 触发并行分析的最小章节数
 * @param fallbackChunkSize     章节拆分失败时的回退分段行数
 * @param minChapterCount       章节拆分最少需识别的章节数，低于此值触发回退策略
 * @param fallbackMinCharacters 触发回退策略时的最低字符数
 * @param targetCharset         目标字符编码
 * @param summaryMaxLength      摘要最大截取字符数
 * @param adPatterns            广告过滤正则模式列表
 */
data class ModerationConfig(
    val lineScoreThreshold: Double = 2.0,
    val chapterScoreThreshold: Double = 18.0,
    val densityBaseChars: Double = 1000.0,
    val compressionScale: Double = 6.5,
    val maxScore: Double = 100.0,
    val repetitionDecay: Double = 0.55,
    val diversityBoostCoeff: Double = 0.15,
    val maxDiversityBoostFactor: Double = 1.6,
    val scoreMultiplierLogBaseA: Double = 3.0,
    val scoreMultiplierPowB: Double = 2.0,
    val explainTopPhrasesLimit: Int = 10,
    val minL0L1SupportForL2: Int = 2,
    val minL0ToL2SupportForL3: Int = 5,
    val parallelChapterAnalysis: Boolean = true,
    val parallelChapterMinCount: Int = 8,
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
