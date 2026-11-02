package com.happyrun.app.update

/**
 * 版本号比较。GitHub 的 tag 通常带 `v` 前缀（`v0.2.0`），而 `versionName` 不带，
 * 这里按数字段逐位比较，缺的段按 0 处理：`1.0` 与 `1.0.0` 相等。
 *
 * 预发布后缀（`-beta1`）不参与比较：本项目只发布正式版，tag 里带后缀也仍然
 * 按前面的数字段判断，避免把 `0.2.0-beta` 当成比 `0.2.0` 新。
 */
object AppVersion {

    /** a > b 返回正数，a < b 返回负数，相等返回 0。 */
    fun compare(a: String, b: String): Int {
        val left = segments(a)
        val right = segments(b)
        val count = maxOf(left.size, right.size)
        for (i in 0 until count) {
            val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    /** 去掉 tag 的 `v` 前缀，用于展示与比较。 */
    fun normalize(version: String): String =
        version.trim().removePrefix("v").removePrefix("V")

    private fun segments(version: String): List<Int> {
        val head = normalize(version).substringBefore('-').substringBefore('+')
        val numbers = Regex("[0-9]+").findAll(head).map { it.value.toIntOrNull() ?: 0 }.toList()
        return numbers.ifEmpty { listOf(0) }
    }
}
