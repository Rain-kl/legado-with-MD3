package io.legado.app.utils.moderation.pattern

import io.legado.app.utils.moderation.model.ModerationLevel
import java.nio.charset.StandardCharsets
import java.util.*
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

    private const val L0 =
        "KOWwv3zlkbvlkJ985aWX5byEfOW/q+aEn3zmrLLmnJt85oSP5Lmx5oOF6L+3fOa9rueDrXzmsZfmtKXmtKV85rm/54OtfOm7j+iFu3zpu4/mu4vmu4t85rm/5ryJ5ryJfOaZtuiOueeahOa2suS9k3znvKDnu7V85aiH576efOWQnuWQkHznmb3mtYp85Y+j5rC0fOa5v+WQu3zllL7mtrJ85aSn6IW/5YaF5L6nfOWWmOaBr3zlqIfllph85L2O5ZCffOi/t+emu3zlkbzlkLguezAsM33mgKXkv4N86Lqr5L2TLnswLDN95Y+R54OrfOWPkeeDrXzohb/ova985ZCO5YWlfOWJjeWFpXzoiJR85b+D6LezLnswLDN95Yqg6YCffOWUh+eToy57MCwzfeW+ruW8oHzouqvkvZMuezAsM33lj5Hova986L2v57u157u1fOWoh+WQn3zovbvpoqR85bCP5YWE5byffOWGheijpHzmiprmkbh85oCl5L+DLnswLDMwfemipOaKlik="

    private const val L1 =
        "KOi1pOijuHzmtqbmu5F85YGa54ixfOe0py57MCw2feeDrXzng60uezAsNn3ntKd85aS55b6XLnswLDN957SnfOaKvemAgXzmir3liqh85L2gLnswLDV956Gs5LqGfOaJk+W8gC57MCwxMH3ohb985oqs6LW3LnswLDV96IW/fOWIhuW8gC57MCwzfeS7lueahOiFv3zoo6QuezEsMTB96Kej5byAfOS4iy57MCw4feijpOWtkHzpobYuezAsM33ov5vljrt85pGH5Yqo552A6IWwfOiHgOmDqHzoh4Dnk6N854ix5oqafOS6suWQu3zmt7HlkLt86L275ZKsfOaPieaNj3zlj4zohb/poqTmipZ86Lqr5L2T5oq95pCQfOWQruWQuHznvKDkvY9856Oo6LmtfOiCjOiCpC57MCwzfeebuOS6snzooaPooasuezAsNn3op6N85pyNLnswLDZ95ruR6JC9fOiEseWIsC57MCw2feiFsHzkuIrooaMuezAsNn3mjoDotbd86KOk5a2QLnswLDZ96KSq5YiwfOWVquWVqnzmjIflsJYuezAsOH3muLjotbB86IWw6IKi54uC5omtfOWWt+a2jHzmuqLmu6F854GM5ruhfOiIjOWwli57MCw4feiIlOi/h3zovbvovbsuezAsNn3llYPlkqx86IW/LnswLDZ957yg5LiKfOi6q+S9ky57MCw2fee0p+i0tCk="

    private const val L2 =
        "KOmrmOa9rnzmj5LlhaV85o+S5ruhfOWkqua3seS6hnzmj5Lov5vmnaV85rexLnswLDN95oy65YWlfOaKveaPkuedgHzmj5LlnKguezAsOH3ph4zpnaJ85LiK5LiL5aWX5byEfOmhtuW8hHzmk43lvIDkuoZ85pON5LqG6L+b5Y67fOaOsOW8gC57MCw1feiFv3zot6rotrR85omT5qGpfOiiq+aPkig/IeWIgCl85Lqk6YWNfOWwhOS6hnzopoHlsITkuoZ85oOz5bCEfOWwhOS6hui/m+WOu3zlsITkuoblh7rmnaV85oi05aWXfOaJqeW8oHzmj5Llh7rmsLR85rWq5rC0fOmqmuawtHzlsYHnnLx86I+K6IqxfOaTjeaIkXzmkpLlsL985omL5oyHLnswLDEwfeS9k+WGheaOoue0onzmuKnng63nmoTnlKzpgZN855Sf5q6W6IWUfOeZvea1ii57MCw1fea2suS9k3znp4HlpIR857K+5rC0fOeMm+aPknzni6Dmj5J85aSn5Yqb5oq96YCBfOW/q+mAn+aKveaPknznvJPmhaLnoJTno6h85pW05qC55rKh5YWlfOmqkeS5mHzkuIrkuIvotbfkvI985YmN5ZCO6IC45YqofOiFv+mrmOS4vnzouqvkvZPlr7nmkp586IKJ5L2T5ouN5omTfOWSleWVvnzlmZfmu4t86IKJ5Ye75aOwfOa5v+a7kXzmva7llrd85rer5Y+rfOa1quWPq3zpqprlj6sp"

    private const val L3 =
        "KOenmOeptHznqbTpgZN8KD88Ieearinlm4rooot86IKJ56m0fOeptOWGhXzlsI8uezAsMX3nqbR85ZCO56m0fOeptOWPo3zohqPlhoV86IKb6ZeofOiCiea0nnzogpvlj6N86IeA57ydfOiCoOiCiXzogqDpgZN857K+5rayfOWwhOeyvnzpmLTojI586IKJ546v5Y+jfOeOieafsXznjonojI586IKJLnswLDF95qOSfOm4oeW3tHzmgKflmah8KD88IeS5jCnpvp8uezAsMX3lpLR85Lmz5bCWfOWJjeWIl+iFunzkubPlpLR85Lya6Zi0fOmprOecvHzmva7lkLl86ZOD5Y+jfOmYtOWbinzlpLHnpoF85bC/5LqG5Ye65p2lfOWwv+mBk3zlrZDlrqt85Lqk5aq+fOS6pOWQiHzogo986KKr5pONfOa3q+iNoXzmt6vmsLR85rer56m0fOa3q+eXknzmt6vmsYF85rer6IWUfOa3q+aAgXzmt6vmtrJ85o+SLnswLDF95aSq5rexfOmhtuW+l+Wlvea3sXzmk43lvIR85bCE5Ye6LnswLDEwfeeZvea1inzlsITlnKguezAsNX3ohLguezAsMn3kuIp85Y+j5LqkfOS9oOWkque0p+S6hnzlsITlnKguezAsMTB95Zi06YeMfOWwhOWcqC57MCw1feWxgeiCoS57MCwyfeS4inzlsITliLAuezAsOH3ouqvkvZPph4x85rKh5YWlLnswLDh96Lqr5L2T6YeMfOW8oOW8gOWQjumdonzlsITlnKguezEsNX3ohbl86Lqr5L2T6YeM5oq95o+SfOmYtOWUh3zpmLTokoJ86b6f5aS0fOWNteibi3zpqprnqbR85rWq56m0fOi0seeptHzlq6nnqbR85bGEfOmqmuWxhHzmtZPnsr585YaF5bCEfOiCieiMjnzpmLPlhbd85YuD6LW3fOiPiuiVvnzlkI7luq186IKg5aOBfOW5sueptCk="

    /** 用于预处理行内容：去除干扰标点和符号 */
    private val NOISE_STRIP_PATTERN = Pattern.compile("[，、｀\\-| @#￥%…&（）—]")

    /** 修复量词中误用句点的正则：{n.m} → {n,m} */
    private val QUANTIFIER_DOT_FIX = Pattern.compile("\\{(\\d+)\\.(\\d+)\\}")

    /** 各级别预编译正则映射 */
    private val compiledPatterns: Map<ModerationLevel, Pattern> = EnumMap<ModerationLevel, Pattern>(
        ModerationLevel::class.java
    ).apply {
        put(ModerationLevel.MILD, compileFromBase64(L0))
        put(ModerationLevel.MODERATE, compileFromBase64(L1))
        put(ModerationLevel.SEVERE, compileFromBase64(L2))
        put(ModerationLevel.CRITICAL, compileFromBase64(L3))
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
