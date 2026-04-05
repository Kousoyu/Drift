package com.kousoyu.drift.data.rule

import org.jsoup.nodes.Element

/**
 * The Rule Evaluator engine.
 * Takes a CSS selector with a custom extractor suffix (e.g. "@text", "@src")
 * and applies it to a JSoup Element.
 */
object RuleEvaluator {

    /**
     * Extracts a string from [element] using the given [rule].
     * Example rules:
     * - "h1@text" -> finds "h1" and calls element.text()
     * - "img.cover@data-src" -> finds "img.cover" and gets the "data-src" attribute
     * - "@href" -> gets the "href" attribute from the current element itself
     * If rule is empty, returns empty string.
     */
    fun getString(element: Element, rule: String?): String {
        if (rule.isNullOrBlank()) return ""

        val parts = rule.split("@")
        val selector = parts[0].trim()
        val extractor = if (parts.size > 1) parts[1].trim() else "text"

        // If selector is empty, it means we operate on the current element (e.g. "@src")
        val targetElement = if (selector.isEmpty()) element else element.selectFirst(selector)
        
        if (targetElement == null) return ""

        val rawStr = when (extractor) {
            "text" -> targetElement.text()
            "html" -> targetElement.html()
            else -> targetElement.attr(extractor)
        }
        
        return rawStr.trim()
    }

    /**
     * Extracts a list of strings using the given [rule].
     */
    fun getStringList(element: Element, listSelector: String, itemRule: String): List<String> {
        if (listSelector.isBlank()) return emptyList()
        val elements = element.select(listSelector)
        return elements.mapNotNull {
            val str = getString(it, itemRule)
            str.ifEmpty { null }
        }
    }
}
