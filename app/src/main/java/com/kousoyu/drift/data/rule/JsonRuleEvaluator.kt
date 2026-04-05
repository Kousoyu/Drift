package com.kousoyu.drift.data.rule

import org.json.JSONArray
import org.json.JSONObject

object JsonRuleEvaluator {

    fun getList(json: String, rule: String): List<JSONObject> {
        var current: Any? = runCatching {
            if (json.trimStart().startsWith("[")) JSONArray(json) else JSONObject(json)
        }.getOrNull() ?: return emptyList()

        val parts = rule.split(".")
        for (part in parts) {
            if (part.isBlank() || part == "$") continue
            
            val arrayMatch = Regex("""(\w+)\[(\d+)]""").find(part)
            if (arrayMatch != null) {
                val key = arrayMatch.groupValues[1]
                val index = arrayMatch.groupValues[2].toInt()
                if (current is JSONObject) {
                    current = current.optJSONArray(key)?.opt(index)
                }
            } else {
                 if (current is JSONObject) {
                     current = current.opt(part)
                 } else if (current is JSONArray && part.all { it.isDigit() }) {
                     current = current.opt(part.toInt())
                 }
            }
        }

        if (current is JSONArray) {
            val list = mutableListOf<JSONObject>()
            for (i in 0 until current.length()) {
                val obj = current.optJSONObject(i)
                if (obj != null) list.add(obj)
            }
            return list
        }
        if (current is JSONObject) {
             return listOf(current)
        }
        return emptyList()
    }

    fun getString(json: JSONObject, rule: String): String {
        if(rule.isBlank()) return ""
        val parts = rule.split(".")
        var current: Any? = json
        for (i in parts.indices) {
            val part = parts[i]
            if (part.isBlank() || part == "$") continue
            
            val isLast = i == parts.size - 1

            val arrayMatch = Regex("""(\w+)\[(\d+)]""").find(part)
            if (arrayMatch != null) {
                val key = arrayMatch.groupValues[1]
                val index = arrayMatch.groupValues[2].toInt()
                if (current is JSONObject) {
                    current = current.optJSONArray(key)?.opt(index)
                    if (isLast) return current?.toString() ?: ""
                } else {
                    return ""
                }
            } else {
                if (current is JSONObject) {
                    if (isLast) {
                        return current.optString(part, "")
                    } else {
                        current = current.opt(part)
                    }
                } else if (current is JSONArray) {
                    if (part.all { it.isDigit() }) {
                        val idx = part.toInt()
                        if (isLast) return current.optString(idx, "")
                        current = current.opt(idx)
                    } else {
                        current = current.optJSONObject(0)?.opt(part)
                        if (isLast) return current?.toString() ?: ""
                    }
                }
            }
        }
        return current?.toString() ?: ""
    }
    
    fun getStringList(json: String, listRule: String, itemRule: String): List<String> {
         var current: Any? = runCatching {
            if (json.trimStart().startsWith("[")) JSONArray(json) else JSONObject(json)
        }.getOrNull() ?: return emptyList()

        val parts = listRule.split(".")
        for (part in parts) {
            if (part.isBlank() || part == "$") continue
            if (current is JSONObject) {
                current = current.opt(part)
            }
        }
        
        val results = mutableListOf<String>()
        if (current is JSONArray) {
             for (i in 0 until current.length()) {
                 val type = current.opt(i)
                 if (itemRule.isBlank()) {
                     results.add(type.toString())
                 } else if (type is JSONObject) {
                     val str = getString(type, itemRule)
                     if (str.isNotEmpty()) results.add(str)
                 }
             }
        }
        return results
    }
}
