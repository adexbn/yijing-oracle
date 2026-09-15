package com.yijing.app.core

/**
 * 384 爻辞 + 白话 数据库。
 * 每条爻：Pair(爻辞原文, 白话)。
 * 爻位 1..6 自下而上（初、二、三、四、五、上）。
 */
object YaoDb {

    private val all: Map<String, List<Pair<String, String>>> = shang + xia

    fun has(name: String): Boolean = all.containsKey(name)

    fun get(name: String): List<Pair<String, String>> = all[name] ?: emptyList()

    fun yao(name: String, line: Int): Pair<String, String> =
        all[name]?.getOrNull(line - 1) ?: ("" to "")
}