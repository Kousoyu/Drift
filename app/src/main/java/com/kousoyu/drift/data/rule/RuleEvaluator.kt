package com.kousoyu.drift.data.rule

import org.jsoup.nodes.Element

/**
 * The Rule Evaluator engine.
 * Takes a CSS selector with a custom extractor suffix and applies it to a Jsoup Element.
 *
 * Supported extractor suffixes:
 *   "@text"           → element.text()
 *   "@html"           → element.html()
 *   "@<attr>"         → element.attr(attr)
 *   "@regex:PATTERN"  → Regex(PATTERN).find(outerHtml())?.groupValues?.get(1)
 *
 * If the selector part is empty (e.g. "@href"), operates on the element itself.
 */
object RuleEvaluator {

    /**
     * Extracts a string from [element] using the given [rule].
     * Examples:
     *   "h1@text"              → text of the h1
     *   "img.cover@data-src"   → data-src attribute of img.cover
     *   "@href"                → href of the current element
     *   "@regex:(\\d+)"        → first capture group matched in outer HTML
     */
    fun getString(element: Element, rule: String?): String {
        if (rule.isNullOrBlank()) return ""

        // ── @regex extractor ────────────────────────────────────────────────
        if (rule.startsWith("@regex:")) {
            val pattern = rule.removePrefix("@regex:").trim()
            return try {
                Regex(pattern).find(element.outerHtml())?.groupValues?.getOrElse(1) { "" }?.trim() ?: ""
            } catch (_: Exception) { "" }
        }

        val atIdx = rule.lastIndexOf('@')
        // atIdx >= 0: extract selector before '@' (empty string when rule starts with '@', e.g. "@href")
        // atIdx <  0: no '@' at all, treat whole rule as CSS selector with default "text" extractor
        val selector  = if (atIdx >= 0) rule.substring(0, atIdx).trim() else rule.trim()
        val extractor = if (atIdx >= 0) rule.substring(atIdx + 1).trim() else "text"

        val targetElement = if (selector.isEmpty()) element else element.selectFirst(selector)
            ?: return ""

        return when (extractor) {
            "text" -> targetElement.text()
            "html" -> targetElement.html()
            else   -> targetElement.attr(extractor)
        }.trim()
    }

    /**
     * Extracts a list of strings from [element] using the given selectors.
     */
    fun getStringList(element: Element, listSelector: String, itemRule: String): List<String> {
        if (listSelector.isBlank()) return emptyList()
        return element.select(listSelector).mapNotNull {
            getString(it, itemRule).ifEmpty { null }
        }
    }
}

