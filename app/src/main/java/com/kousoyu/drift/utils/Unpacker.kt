package com.kousoyu.drift.utils

/**
 * Helper class to unpack JavaScript code compressed by [packer](http://dean.edwards.name/packer/).
 *
 * Source code of packer can be found [here](https://github.com/evanw/packer/blob/master/packer.js).
 */
object Unpacker {

    /**
     * Unpacks JavaScript code compressed by packer.
     *
     * Specify [left] and [right] to unpack only the data between them.
     *
     * Note: single quotes `\'` in the data will be replaced with double quotes `"`.
     */
    fun unpack(script: String, left: String? = null, right: String? = null): String = unpack(SubstringExtractor(script), left, right)

    /**
     * Unpacks JavaScript code compressed by packer.
     *
     * Specify [left] and [right] to unpack only the data between them.
     *
     * Note: single quotes `\'` in the data will be replaced with double quotes `"`.
     */
    fun unpack(script: SubstringExtractor, left: String? = null, right: String? = null): String {
        // Handle both )) and ) cases by extracting to .split('|') and then picking the rest
        var packed = script.substringBetween("}('", ".split('|')")
        if (packed.isNotEmpty()) {
            packed = packed.substringBeforeLast("',", packed) + "'," // Reattach delimiter if needed, or better, just use regex/substring on raw string
        }
        
        // Actually, let's just use string operations on the original string inside SubstringExtractor
        var rawPacked = script.rawString
        var extracted = rawPacked.substringAfter("}('", "")
        if (extracted.contains(".split('|'),0,{}))")) {
            extracted = extracted.substringBefore(".split('|'),0,{}))")
        } else if (extracted.contains(".split('|'),0,{})")) {
            extracted = extracted.substringBefore(".split('|'),0,{})")
        } else {
            extracted = extracted.substringBefore(".split('|')")
        }
        
        val packedStr = extracted.replace("\\'", "\"")

        val parser = SubstringExtractor(packedStr)
        val data: String
        if (left != null && right != null) {
            data = parser.substringBetween(left, right)
            parser.skipOver("',")
        } else {
            data = parser.substringBefore("',")
        }
        if (data.isEmpty()) return ""

        val dictionary = parser.substringBetween("'", "'").split("|")
        val size = dictionary.size

        return wordRegex.replace(data) {
            val key = it.value
            val index = parseRadix62(key)
            if (index >= size) return@replace key
            dictionary[index].ifEmpty { key }
        }
    }

    private val wordRegex by lazy { Regex("""\w+""") }

    private fun parseRadix62(str: String): Int {
        var result = 0
        for (ch in str.toCharArray()) {
            result = result * 62 + when {
                ch.code <= '9'.code -> { // 0-9
                    ch.code - '0'.code
                }

                ch.code >= 'a'.code -> { // a-z
                    // ch - 'a' + 10
                    ch.code - ('a'.code - 10)
                }

                else -> { // A-Z
                    // ch - 'A' + 36
                    ch.code - ('A'.code - 36)
                }
            }
        }
        return result
    }
}
