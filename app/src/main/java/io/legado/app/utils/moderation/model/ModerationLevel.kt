package io.legado.app.utils.moderation.model

/**
 * 内容审核敏感级别枚举。
 *
 * 权重从低到高：MILD(1) → MODERATE(2) → SEVERE(3)。
 * 评分公式：lineScore += weight × matchCount
 */
enum class ModerationLevel(val weight: Int) {

    /** 轻度敏感 —— 暗示性描写 */
    MILD(1),

    /** 中度敏感 —— 较明确的描写 */
    MODERATE(2),

    /** 高度敏感 —— 直接露骨描写 */
    SEVERE(3)
}
