package io.legado.app.utils.moderation

import io.legado.app.utils.moderation.config.ModerationConfig
import io.legado.app.utils.moderation.core.ChapterSplitter
import io.legado.app.utils.moderation.core.ContentAnalyzer
import io.legado.app.utils.moderation.core.TextFileReader
import io.legado.app.utils.moderation.model.AnalysisResult
import java.io.File
import java.io.IOException

/**
 * 文本审核引擎 —— 统一门面（Facade）。
 *
 * 组合编码检测、广告过滤、章节拆分、内容分析的完整流程，
 * 对外提供一次调用即出结果的简洁 API。
 *
 * ### 使用示例
 *
 * ```kotlin
 * // 1. 默认配置 — 最简用法
 * val result = TextModerationService.create()
 *     .analyzeFile(File("novel.txt"))
 *
 * // 2. 自定义配置
 * val config = ModerationConfig(
 *     lineScoreThreshold = 3.0,
 *     chapterScoreThreshold = 5.0
 * )
 * val result = TextModerationService.create(config)
 *     .analyzeFile(File("novel.txt"))
 *
 * // 3. 分析内存中的文本
 * val result = TextModerationService.create()
 *     .analyzeText(textContent)
 * ```
 *
 * 线程安全：实例无可变状态，可在多线程间安全共享。
 */
class TextModerationService private constructor(val config: ModerationConfig) {

    private val splitter = ChapterSplitter(config)
    private val analyzer = ContentAnalyzer(config)

    companion object {
        /** 使用默认配置创建审核服务 */
        fun create(): TextModerationService =
            TextModerationService(ModerationConfig.defaults())

        /** 使用自定义配置创建审核服务 */
        fun create(config: ModerationConfig): TextModerationService =
            TextModerationService(config)
    }

    // ── 公共 API ─────────────────────────────────────────────

    /**
     * 分析指定文件的文本内容。
     *
     * @param file 文本文件
     * @return 分析结果
     * @throws IOException              文件读取失败
     * @throws IllegalArgumentException 文件不存在或不是常规文件
     */
    @Throws(IOException::class)
    fun analyzeFile(file: File): AnalysisResult {
        validateFile(file)
        val chapters = splitter.split(file)
        return analyzer.analyze(chapters)
    }

    /**
     * 分析内存中的文本字符串。
     *
     * @param text 文本内容
     * @return 分析结果
     * @throws IllegalArgumentException 文本为空
     */
    fun analyzeText(text: String): AnalysisResult {
        require(text.isNotBlank()) { "文本内容不能为空" }

        val lines = TextFileReader.readLinesFromString(text)
        val chapters = splitter.splitFromLines(lines)
        return analyzer.analyze(chapters)
    }

    // ── 内部校验 ─────────────────────────────────────────────

    private fun validateFile(file: File) {
        require(file.exists()) { "文件不存在: ${file.absolutePath}" }
        require(file.isFile) { "不是常规文件: ${file.absolutePath}" }
    }
}
