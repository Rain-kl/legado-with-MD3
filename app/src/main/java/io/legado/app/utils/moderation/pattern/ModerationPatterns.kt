package io.legado.app.utils.moderation.pattern

import io.legado.app.utils.moderation.model.ModerationLevel
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.EnumMap
import java.util.regex.Pattern

/**
 * 内容审核正则模式管理器。
 *
 * 将敏感词正则以 Base64 形式存储，运行时解码并预编译为 [Pattern]，
 * 避免源码中直接出现敏感词，同时通过预编译提升匹配性能。
 *
 * 线程安全：所有字段均为不可变的，单例在初始化后发布安全。
 */
object ModerationPatterns {

    // ── Base64 编码的正则表达式（避免源码出现明文敏感词） ────────

    private const val MILD_B64 =
        "KOi1pOijuHzpq5jmva585o+S5YWlfOaPkua7oXzmiZPlvIAuezAsMTB95Y+M6IW/fOWkqua3seS6hnzmub/mt4vmt4t86buP5ruL5ruLfOm7j+iFu3zmva7ng6185rGX5rSl5rSlfOa5v+eDrXzmmbbojrnnmoTmtrLkvZN85rm/5ryJ5ryJfOW/q+aEn3zntKcuezAuNn3ng61854OtLnswLjZ957SnfOWkueW+ly57MCwzfee0p3zmhI/kubHmg4Xov7d85aG15b6XfOaKvemAgXzmk43miJF85bGB55y8fOWkhOS6hnzlpKfohb/lhoXkvqd85L2gLnswLDV956Gs5LqGfOa2pua7kea2snzmtqbmu5HliYJ85ram5ruR5rK5fOWBmueIsXzlj6PmsLR86I+K6IqxfOiHgOmDqHzmkpLlsL985o+S6L+b5p2lfOWIhuW8gC57MCwzfeS7lueahOWPjOiFv3zllL7mtrJ86KOkLnsxLDEwfeino+W8gHzkuIsuezAsOH3oo6TlrZB85pGH5Yqo552A6IWwKQ=="

    private const val MODERATE_B64 =
        "KOaItOWll3znlJ/mrpbohZR855m95rWK55qE5ray5L2TfOa4qeeDreeahOeUrOmBk3zopoHlsITkuoZ85omp5bygfOaPkuWHuuawtHzkubMuezEsNn3mtrJ85omL5oyHLnswLDEwfeS9k+WGheaOoue0onzmir3mj5LnnYB85o+S5ZyoLnswLDh96YeM6Z2ifOengeWkhHzmg7PlsIR85bCE5LqG6L+b5Y67fOWwhOS6huWHuuadpXzot6jotrR85omT5qGpfOiiq+aPkig/IeWIgCl85Lqk6YWNfOWwhOWIsC57MCw4fei6q+S9k+mHjHzmsqHlhaUuezDvvIw4fei6q+S9k+mHjHzkuIrkuIvlpZflvIR86IeA55OjfOiEseaOiS57MCw2feijpOWtkHznp4HlpIR85pON5byA5LqGfOmhtuW8hHzmk43kuobov5vljrt85o6w5byALnswLDV96IW/KQ=="

    private const val SEVERE_B64 =
        "KOenmOeptHznqbTpgZN8KD88Ieearinlm4rooot86IKJ56m0fOeptOWGhXzlsI8uezAsMX3nqbR85ZCO56m0fOeptOWPo3zohqPlhoV86IKb6ZeofOiCiea0nnzogpvlj6N86IeA57ydfOiCoOiCiXzogqDpgZN857K+5rayfOWwhOeyvnzpmLTojI586IKJ546v5Y+jfOeOieafsXznjonojI586IKJLnswLDF95qOSfOm4oeW3tHzmgKflmah8KD88IeS5jCnpvp8uezAsMX3lpLR85Lmz5bCWfOWJjeWIl+iFunzkubPlpLR85Lya6Zi0fOmprOecvHzmva7lkLl85ZOD5Y+jfOmYtOWbinzlpLHnpoF85bC/5LqG5Ye65p2lfOWwv+mBk3zlrZDlrqt85Lqk5aq+fOS6pOWQiHzogo986KKr5pONfOa3q+iNoXzmt6vmsLR85rer6IWUfOa3q+aAgXzmt6vmtrJ857K+5rC0fOaPki57MCwxfeWkqua3sXzpobblvpflpb3mt7F85pON5byEfOWwhOWHui57MCwxMH3nm73mtYp85bCE5ZyoLnswLDV96IS4LnswLDJ95LiKfOWPo+S6pHzkvaDlpKrntKfkuoZ85bCE5ZyoLnswLDEwfeWYtOmHjHzlsITlnKguezAsNX3lsYHogqEuezAsMn3kuIp85Zyo5LuW55qE6Lqr5L2T6YeMfOaTjeS6hui/m+WOu3zlvKDlvIDlkI7pnaJ85bCE5ZyoLnsxLDV96IW5fOi6q+S9k+mHjOaKveaPkik="

    /** 用于预处理行内容：去除干扰标点和符号 */
    private val NOISE_STRIP_PATTERN = Pattern.compile("[，、｀\\-| @#￥%…&（）—]")

    /** 修复量词中误用句点的正则：{n.m} → {n,m} */
    private val QUANTIFIER_DOT_FIX = Pattern.compile("\\{(\\d+)\\.(\\d+)\\}")

    /** 各级别预编译正则映射 */
    private val compiledPatterns: Map<ModerationLevel, Pattern> = EnumMap<ModerationLevel, Pattern>(
        ModerationLevel::class.java
    ).apply {
        put(ModerationLevel.MILD, compileFromBase64(MILD_B64))
        put(ModerationLevel.MODERATE, compileFromBase64(MODERATE_B64))
        put(ModerationLevel.SEVERE, compileFromBase64(SEVERE_B64))
    }

    /**
     * 获取指定级别的预编译正则 Pattern。
     */
    fun getPattern(level: ModerationLevel): Pattern =
        compiledPatterns[level] ?: throw IllegalArgumentException("No pattern for level: $level")

    /**
     * 获取所有级别的 Pattern 映射（不可变视图）。
     */
    fun getAllPatterns(): Map<ModerationLevel, Pattern> = compiledPatterns.toMap()

    /**
     * 去除行内容中的干扰字符，便于正则匹配。
     *
     * @param line 原始行
     * @return 去除噪声后的行
     */
    fun stripNoise(line: String): String =
        NOISE_STRIP_PATTERN.matcher(line).replaceAll("")

    // ── 内部方法 ─────────────────────────────────────────────

    private fun compileFromBase64(base64Str: String): Pattern {
        var decoded = String(
            Base64.getDecoder().decode(base64Str),
            StandardCharsets.UTF_8
        )
        // 修正原始 Python 正则中的语法差异：
        // 1. {0.6} → {0,6}（Python re 模块容忍句点作为量词分隔符）
        // 2. {0，8} → {0,8}（全角逗号 → 半角逗号）
        decoded = QUANTIFIER_DOT_FIX.matcher(decoded).replaceAll("{$1,$2}")
        decoded = decoded.replace('，', ',')
        return Pattern.compile(decoded)
    }
}
