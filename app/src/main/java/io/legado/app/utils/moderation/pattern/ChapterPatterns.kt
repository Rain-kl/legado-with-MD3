package io.legado.app.utils.moderation.pattern

import java.util.regex.Pattern

/**
 * 章节标题识别正则模式。
 *
 * 所有 Pattern 预编译并缓存，线程安全。
 */
object ChapterPatterns {

    // ── 预处理正则（用于去除行首标记） ─────────────────────────

    val prePatterns: List<Pattern> = compileAll(
        "^#(.*)"
    )

    // ── 主章节标题正则 ──────────────────────────────────────

    val mainPatterns: List<Pattern> = compileAll(
        "^#(.*)",
        "^[ ]{0,5}(?:第 {0,1}[\\d〇零一二两三四五六七八九十百]{0,5}[\\s ]{0,1}章)[\\s ]{0,2}(.{0,35})$",
        "^[ ]{0,5}(Chapter {0,1}[\\d〇零一二两三四五六七八九十百]{0,5}[ ]{0,5})$",
        "^[ ]{0,5}\\d{1,3}.{0,3}[ ]{0,5}$",
        "^\\d{1,3}-.{1,10}$",
        "^\\d{1,3}$",
        "^ {0,2}☆、.{1,10}$",
        "^\\d{1,3}\\D{1,15}$",
        "^[零一二两三四五六七八九十百]{1,3}$"
    )

    // ── 番外标题正则 ────────────────────────────────────────

    val extraPatterns: List<Pattern> = compileAll(
        "^(番外(?!完)[\\d〇零一二两三四五六七八九十百]{1,2})$",
        "^(番外(?!完)[\\d〇零一二两三四五六七八九十百]{1,2}.{1,15})$",
        "^(番外(?!完)).{0,10}$",
        "《.*番外.*》"
    )

    // ── 排除正则（匹配这些模式的行不视为标题） ──────────────────

    val excludePatterns: List<Pattern> = compileAll(
        "[。]$"
    )

    /**
     * 获取主正则 + 番外正则的合并列表（用于最终拆分）。
     *
     * @param mainIndex 匹配频次最高的主正则索引
     * @return 拆分用正则列表
     */
    fun getSplitPatterns(mainIndex: Int): List<Pattern> {
        if (mainIndex < 0 || mainIndex >= mainPatterns.size) {
            return extraPatterns
        }
        return listOf(mainPatterns[mainIndex]) + extraPatterns
    }

    /** 获取全部可能的拆分正则（主 + 番外） */
    fun getAllSplitPatterns(): List<Pattern> = mainPatterns + extraPatterns

    // ── 内部方法 ─────────────────────────────────────────────

    private fun compileAll(vararg regexes: String): List<Pattern> =
        regexes.map { Pattern.compile(it) }
}
