package com.happyrun.app.vpn

/**
 * 服务端挑战的两种格式，解析错了就永远弹不出输入框（这就是第一次实现漏掉 CR_TEXT 的原因）。
 *
 * - 静态挑战：`>INFOMSG:CR_TEXT:<flags>:<文本>`，答复用 `cr-response <base64>`；
 * - 动态挑战：`>PASSWORD:Verification Failed: 'Auth' ['AUTH_FAILED,CRV1:<flags>:<b64 提示>:<b64 echo>:<b64 state>']`，
 *   答复是"用户名不变 + 密码位填验证码"。
 */
object ChallengeParser {

    /**
     * `CR_TEXT:R,E:请输入短信验证码：` → `请输入短信验证码：`
     *
     * 文本本身可能含冒号，所以只切掉 flags 那一段；flags 只由 R/E/逗号组成，
     * 不满足就不当 flags（避免把 "CR_TEXT:http://x" 这类文本截坏）。
     */
    fun parseCrText(raw: String): String? {
        if (!raw.startsWith("CR_TEXT:")) return null
        val rest = raw.removePrefix("CR_TEXT:")
        val index = rest.indexOf(':')
        if (index < 0) return rest.trim().ifBlank { null }
        val flags = rest.substring(0, index)
        val looksLikeFlags = flags.isNotEmpty() && flags.all { it in "RE, " }
        return (if (looksLikeFlags) rest.substring(index + 1) else rest).trim().ifBlank { null }
    }

    /**
     * 从失败文案里取 CRV1 的提示文本（base64）。取不到返回 null。
     * flags 可能是 "R,E" 或 "E"，所以按冒号切开后提示在索引 1。
     */
    fun parseDynamicPrompt(raw: String, decoder: (String) -> String?): String? {
        val marker = raw.indexOf("CRV1:")
        if (marker < 0) return null
        val parts = raw.substring(marker + "CRV1:".length).split(":")
        val encoded = parts.getOrNull(1) ?: return null
        if (encoded.isBlank()) return null
        return decoder(encoded)?.trim()?.ifBlank { null }
    }
}
