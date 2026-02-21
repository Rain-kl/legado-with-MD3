package io.legado.app.utils.textmoderation

import io.legado.app.utils.moderation.TextModerationService
import io.legado.app.utils.moderation.config.ModerationConfig
import io.legado.app.utils.moderation.core.AdFilter
import io.legado.app.utils.moderation.core.ChapterSplitter
import io.legado.app.utils.moderation.core.ContentAnalyzer
import io.legado.app.utils.moderation.core.TextFileReader
import io.legado.app.utils.moderation.model.AnalysisResult
import io.legado.app.utils.moderation.model.ChapterAnalysis
import io.legado.app.utils.moderation.model.ModerationLevel
import io.legado.app.utils.moderation.pattern.ChapterPatterns
import io.legado.app.utils.moderation.pattern.ModerationPatterns
import org.junit.Assert.*
import org.junit.Test
import java.util.LinkedHashMap

/**
 * 文本审核工具 Kotlin 版功能测试。
 */
class TextModerationTest {

    // ══════════════════════════════════════════════════════════
    // ModerationConfig 测试
    // ══════════════════════════════════════════════════════════

    @Test
    fun `defaults config has expected values`() {
        val config = ModerationConfig.defaults()
        assertEquals(2.0, config.lineScoreThreshold, 0.001)
        assertEquals(3.5, config.chapterScoreThreshold, 0.001)
        assertEquals(20, config.fallbackChunkSize)
        assertEquals(5, config.minChapterCount)
        assertEquals(10_000L, config.fallbackMinCharacters)
        assertEquals(200, config.summaryMaxLength)
        assertEquals(4, config.adPatterns.size)
    }

    @Test
    fun `custom config overrides work`() {
        val config = ModerationConfig(
            lineScoreThreshold = 5.0,
            chapterScoreThreshold = 8.0,
            fallbackChunkSize = 50
        )
        assertEquals(5.0, config.lineScoreThreshold, 0.001)
        assertEquals(8.0, config.chapterScoreThreshold, 0.001)
        assertEquals(50, config.fallbackChunkSize)
        // defaults still intact
        assertEquals(5, config.minChapterCount)
    }

    // ══════════════════════════════════════════════════════════
    // ModerationLevel 测试
    // ══════════════════════════════════════════════════════════

    @Test
    fun `moderation levels have correct weights`() {
        assertEquals(1, ModerationLevel.MILD.weight)
        assertEquals(2, ModerationLevel.MODERATE.weight)
        assertEquals(3, ModerationLevel.SEVERE.weight)
    }

    @Test
    fun `moderation level entries count`() {
        assertEquals(3, ModerationLevel.entries.size)
    }

    // ══════════════════════════════════════════════════════════
    // ChapterPatterns 测试
    // ══════════════════════════════════════════════════════════

    @Test
    fun `main patterns match expected chapter titles`() {
        val patterns = ChapterPatterns.mainPatterns
        assertTrue(patterns.size >= 9)

        // 测试中文章节标题匹配
        assertTrue(patterns[1].matcher("第一章 开始").matches())
        assertTrue(patterns[1].matcher("第10章 结束").matches())
        assertTrue(patterns[1].matcher("  第三章 故事").matches())

        // 测试纯数字标题
        assertTrue(patterns[5].matcher("123").matches())
        assertTrue(patterns[5].matcher("1").matches())

        // 测试中文数字
        assertTrue(patterns[8].matcher("一").matches())
        assertTrue(patterns[8].matcher("十二").matches())
    }

    @Test
    fun `extra patterns match fanwai titles`() {
        val patterns = ChapterPatterns.extraPatterns
        assertTrue(patterns[0].matcher("番外一").matches())
        assertTrue(patterns[2].matcher("番外 插曲").matches())
    }

    @Test
    fun `exclude patterns reject lines ending with period`() {
        val patterns = ChapterPatterns.excludePatterns
        // excludePatterns 使用 matches()，[。]$ 仅完整匹配单字符 "。"
        assertTrue(patterns[0].matcher("。").matches())
        // 完整句子不会被 matches() 匹配（原始设计如此：仅排除独立句号行）
        assertFalse(patterns[0].matcher("这是一个句子。").matches())
        // 但 find() 可以匹配尾部句号
        assertTrue(patterns[0].matcher("这是一个句子。").find())
    }

    @Test
    fun `getSplitPatterns returns main plus extras`() {
        val splitPatterns = ChapterPatterns.getSplitPatterns(0)
        // 1 main + all extras
        assertEquals(1 + ChapterPatterns.extraPatterns.size, splitPatterns.size)
    }

    @Test
    fun `getSplitPatterns with invalid index returns only extras`() {
        val splitPatterns = ChapterPatterns.getSplitPatterns(-1)
        assertEquals(ChapterPatterns.extraPatterns.size, splitPatterns.size)
    }

    @Test
    fun `getAllSplitPatterns returns all main plus extras`() {
        val all = ChapterPatterns.getAllSplitPatterns()
        assertEquals(
            ChapterPatterns.mainPatterns.size + ChapterPatterns.extraPatterns.size,
            all.size
        )
    }

    // ══════════════════════════════════════════════════════════
    // ModerationPatterns 测试
    // ══════════════════════════════════════════════════════════

    @Test
    fun `all moderation patterns compile successfully`() {
        // 确保 Base64 解码和编译都成功
        for (level in ModerationLevel.entries) {
            assertNotNull(ModerationPatterns.getPattern(level))
        }
    }

    @Test
    fun `getAllPatterns returns all three levels`() {
        val all = ModerationPatterns.getAllPatterns()
        assertEquals(3, all.size)
        assertTrue(all.containsKey(ModerationLevel.MILD))
        assertTrue(all.containsKey(ModerationLevel.MODERATE))
        assertTrue(all.containsKey(ModerationLevel.SEVERE))
    }

    @Test
    fun `stripNoise removes interference characters`() {
        val noisy = "你好，世界—测试｀内容"
        val cleaned = ModerationPatterns.stripNoise(noisy)
        assertFalse(cleaned.contains("，"))
        assertFalse(cleaned.contains("—"))
        assertFalse(cleaned.contains("｀"))
        assertTrue(cleaned.contains("你好"))
        assertTrue(cleaned.contains("世界"))
    }

    // ══════════════════════════════════════════════════════════
    // AdFilter 测试
    // ══════════════════════════════════════════════════════════

    @Test
    fun `ad filter removes ad lines`() {
        val config = ModerationConfig.defaults()
        val filter = AdFilter(config)

        val lines = listOf(
            "这是正文内容",
            "请关注公众号获取更多",
            "第一章 开始",
            "作品来自互联网，仅供学习",
            "正常的一行",
            "--------------------"
        )

        val filtered = filter.filter(lines)
        assertEquals(3, filtered.size)
        assertEquals("这是正文内容", filtered[0])
        assertEquals("第一章 开始", filtered[1])
        assertEquals("正常的一行", filtered[2])
    }

    @Test
    fun `ad filter with empty list`() {
        val filter = AdFilter(ModerationConfig.defaults())
        val filtered = filter.filter(emptyList())
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `ad filter keeps all lines when no ads`() {
        val filter = AdFilter(ModerationConfig.defaults())
        val lines = listOf("第一行", "第二行", "第三行")
        val filtered = filter.filter(lines)
        assertEquals(3, filtered.size)
    }

    // ══════════════════════════════════════════════════════════
    // TextFileReader 测试
    // ══════════════════════════════════════════════════════════

    @Test
    fun `readLinesFromString normalizes and filters`() {
        val content = "\uFEFF第一行\r\n\u3000第二行\n\n  \n第三行"
        val lines = TextFileReader.readLinesFromString(content)
        assertEquals(3, lines.size)
        assertEquals("第一行", lines[0])
        assertEquals("第二行", lines[1])
        assertEquals("第三行", lines[2])
    }

    @Test
    fun `readLinesFromString empty string`() {
        val lines = TextFileReader.readLinesFromString("")
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `readLinesFromString removes BOM only at start`() {
        val content = "\uFEFF内容"
        val lines = TextFileReader.readLinesFromString(content)
        assertEquals(1, lines.size)
        assertEquals("内容", lines[0])
    }

    // ══════════════════════════════════════════════════════════
    // ChapterSplitter 测试
    // ══════════════════════════════════════════════════════════

    @Test
    fun `splitFromLines creates chapters from titled text`() {
        val splitter = ChapterSplitter(ModerationConfig.defaults())
        val lines = listOf(
            "前言内容",
            "第一章 起始",
            "第一章正文第一行",
            "第一章正文第二行",
            "第二章 发展",
            "第二章正文第一行",
            "第三章 高潮",
            "第三章正文",
            "第四章 结局",
            "第四章正文",
            "第五章 尾声",
            "第五章正文",
            "第六章 后记",
            "第六章正文"
        )

        val chapters = splitter.splitFromLines(lines)

        // 应该至少有 7 个章节 (0=前言 + 6个正文章节)
        assertTrue("章节数应 >= 7，实际: ${chapters.size}", chapters.size >= 7)

        // 第 0 章应该有 summary 前缀
        val firstChapter = chapters[0]!!
        assertTrue(firstChapter[0].startsWith("summary:"))
    }

    @Test
    fun `splitFromLines fallback on short text with no chapters`() {
        val config = ModerationConfig(
            minChapterCount = 3,
            fallbackMinCharacters = 5,
            fallbackChunkSize = 2
        )
        val splitter = ChapterSplitter(config)
        val lines = listOf("行1内容", "行2内容", "行3内容", "行4内容", "行5内容")

        val chapters = splitter.splitFromLines(lines)
        // 5 lines / chunkSize 2 = 3 chapters (0,1,2)
        assertTrue("应触发回退拆分，章节数>=3, 实际: ${chapters.size}", chapters.size >= 3)
    }

    @Test
    fun `splitFromLines empty input`() {
        val splitter = ChapterSplitter(ModerationConfig.defaults())
        val chapters = splitter.splitFromLines(emptyList())
        assertTrue(chapters.isEmpty())
    }

    @Test
    fun `splitFromLines with fanwai chapters`() {
        val splitter = ChapterSplitter(ModerationConfig.defaults())
        val lines = listOf(
            "第一章 开始",
            "内容一",
            "第二章 继续",
            "内容二",
            "第三章 再续",
            "内容三",
            "第四章 还续",
            "内容四",
            "第五章 最后",
            "内容五",
            "番外一",
            "番外内容"
        )

        val chapters = splitter.splitFromLines(lines)
        assertTrue("应识别番外章节", chapters.size >= 6)
    }

    // ══════════════════════════════════════════════════════════
    // ContentAnalyzer 测试
    // ══════════════════════════════════════════════════════════

    @Test
    fun `analyze empty chapters returns empty result`() {
        val analyzer = ContentAnalyzer(ModerationConfig.defaults())
        val result = analyzer.analyze(LinkedHashMap())
        assertEquals(0.0, result.totalScore, 0.001)
        assertEquals(0, result.flaggedChapters)
        assertEquals(0, result.totalChapters)
        assertTrue(result.details.isEmpty())
    }

    @Test
    fun `analyze normal text returns low scores`() {
        val analyzer = ContentAnalyzer(ModerationConfig.defaults())
        val chapters = LinkedHashMap<Int, List<String>>().apply {
            put(0, listOf("summary: 前言", "这是一个正常的故事"))
            put(1, listOf("第一章 开始", "阳光明媚的早晨", "他走进了教室"))
            put(2, listOf("第二章 学习", "他打开了课本", "认真地做笔记"))
        }

        val result = analyzer.analyze(chapters)
        assertEquals(3, result.totalChapters)
        assertEquals(0, result.flaggedChapters)
        assertEquals(0.0, result.totalScore, 0.001)
        assertTrue(result.details.isEmpty())
    }

    @Test
    fun `analyze extracts summary from chapter 0`() {
        val analyzer = ContentAnalyzer(ModerationConfig.defaults())
        val chapters = LinkedHashMap<Int, List<String>>().apply {
            put(0, listOf("summary: 这是摘要内容"))
            put(1, listOf("第一章 正文"))
        }

        val result = analyzer.analyze(chapters)
        assertTrue(result.summary.isNotEmpty())
        assertTrue(result.summary.contains("这是摘要内容"))
    }

    // ══════════════════════════════════════════════════════════
    // AnalysisResult 测试
    // ══════════════════════════════════════════════════════════

    @Test
    fun `flaggedRatePercent formats correctly`() {
        val result = AnalysisResult(
            totalScore = 10.0,
            flaggedChapters = 3,
            totalChapters = 10,
            flaggedRate = 0.3,
            totalCharacters = 50000,
            summary = "测试",
            details = emptyList()
        )
        assertEquals("30.0%", result.flaggedRatePercent)
    }

    @Test
    fun `totalCharactersFormatted shows wan`() {
        val result = AnalysisResult(
            totalScore = 0.0,
            flaggedChapters = 0,
            totalChapters = 0,
            flaggedRate = 0.0,
            totalCharacters = 50000,
            summary = "",
            details = emptyList()
        )
        assertEquals("5.00万", result.totalCharactersFormatted)
    }

    // ══════════════════════════════════════════════════════════
    // ChapterAnalysis 测试
    // ══════════════════════════════════════════════════════════

    @Test
    fun `chapterAnalysis isFlagged threshold`() {
        val notFlagged = ChapterAnalysis(
            title = "测试", score = 3.4, flaggedLines = listOf("line1")
        )
        assertFalse(notFlagged.isFlagged)

        val flagged = ChapterAnalysis(
            title = "测试", score = 3.5, flaggedLines = listOf("line1")
        )
        assertTrue(flagged.isFlagged)
    }

    // ══════════════════════════════════════════════════════════
    // TextModerationService 集成测试
    // ══════════════════════════════════════════════════════════

    @Test
    fun `service analyzeText with normal content`() {
        val service = TextModerationService.create()
        val text = buildString {
            appendLine("第一章 开始")
            appendLine("这是一个温馨的故事")
            appendLine("第二章 发展")
            appendLine("主人公努力学习")
            appendLine("第三章 结局")
            appendLine("最终取得了好成绩")
        }

        val result = service.analyzeText(text)
        assertEquals(0, result.flaggedChapters)
        assertTrue(result.totalChapters > 0)
        assertTrue(result.totalCharacters > 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `service analyzeText with blank text throws`() {
        TextModerationService.create().analyzeText("   ")
    }

    @Test
    fun `service create with custom config`() {
        val config = ModerationConfig(lineScoreThreshold = 5.0)
        val service = TextModerationService.create(config)
        assertEquals(5.0, service.config.lineScoreThreshold, 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `service analyzeFile with nonexistent file throws`() {
        val service = TextModerationService.create()
        service.analyzeFile(java.io.File("/nonexistent/path/file.txt"))
    }

    @Test
    fun `full pipeline integration test`() {
        val service = TextModerationService.create()
        val text = buildString {
            appendLine("这是一本小说的摘要")
            appendLine("第一章 平静的生活")
            repeat(5) { appendLine("阳光洒在窗台上，微风轻拂。") }
            appendLine("第二章 冒险开始")
            repeat(5) { appendLine("他踏上了旅途，前方充满未知。") }
            appendLine("第三章 挑战来临")
            repeat(5) { appendLine("困难接踵而至，但他没有放弃。") }
            appendLine("第四章 转机")
            repeat(5) { appendLine("一个意外的发现改变了一切。") }
            appendLine("第五章 胜利")
            repeat(5) { appendLine("最终他克服了所有困难。") }
        }

        val result = service.analyzeText(text)

        // 基本断言
        assertTrue(result.totalChapters > 0)
        assertTrue(result.totalCharacters > 0)
        assertTrue(result.flaggedRate >= 0.0)
        assertTrue(result.flaggedRate <= 1.0)
        assertNotNull(result.flaggedRatePercent)
        assertNotNull(result.totalCharactersFormatted)
        assertNotNull(result.toString())

        // 正常内容不应标记
        assertEquals(0, result.flaggedChapters)
        assertEquals(0.0, result.totalScore, 0.001)
    }
}
