package io.legado.app.utils.moderation.model

/**
 * 内容审核敏感级别枚举。
 *
 * 权重从低到高：L0(1) → L1(3) → L2(7) → L3(15)。
 * 评分公式：rawScore += weight × matchCount
 */
enum class ModerationLevel(val weight: Int) {

    /** L0 轻微暗示/泛化情感词 */
    MILD(1),

    /** L1 暧昧前戏/感官氛围词 */
    MODERATE(3),

    /** L2 明确动作/强风险词 */
    SEVERE(7),

    /** L3 露骨器官/极高风险词 */
    CRITICAL(15)
}
