package io.legado.app.utils.moderation.core

import io.legado.app.utils.moderation.config.ModerationConfig
import java.util.regex.Pattern

/**
 * 广告与杂质行过滤器。
 *
 * 根据配置中的广告正则模式列表，过滤掉文本中的广告行、
 * 版权声明、分隔线等非正文内容。
 *
 * 线程安全：预编译模式列表不可变。
 */
class AdFilter(config: ModerationConfig) {

    private val adPatterns: List<Pattern> = config.adPatterns.map { Pattern.compile(it) }

    /**
     * 过滤广告行，返回过滤后的行列表。
     *
     * @param lines 原始行列表
     * @return 过滤后的行列表
     */
    fun filter(lines: List<String>): List<String> =
        lines.filter { line -> !isAd(line) }

    /** 判断单行是否为广告 */
    private fun isAd(line: String): Boolean =
        adPatterns.any { it.matcher(line).find() }
}
