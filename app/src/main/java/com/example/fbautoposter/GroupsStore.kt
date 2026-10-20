package com.example.fbautoposter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Locally saved groups. A discovered group may have only a name (no Facebook URL). */
data class FbGroup(val id: String, val name: String, val url: String = "")

object GroupsStore {
    private const val PREFS = "fb_groups_v2"
    private const val GROUPS = "groups"
    private const val LISTS = "lists"

    fun all(context: Context): List<FbGroup> = try {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(GROUPS, "[]") ?: "[]"
        val data = JSONArray(text)
        (0 until data.length()).map { i ->
            val obj = data.getJSONObject(i)
            FbGroup(obj.getString("id"), obj.getString("name"), obj.optString("url", ""))
        }
    } catch (_: Exception) { emptyList() }

    fun add(context: Context, namesAndUrls: List<Pair<String, String>>): Int {
        val current = all(context).toMutableList()
        var added = 0
        for ((rawName, rawUrl) in namesAndUrls) {
            val url = canonicalUrl(rawUrl)
            val name = rawName.trim().take(160).ifBlank { url.substringAfter("/groups/").substringBefore('/') }
            if (name.length < 2) continue
            val index = current.indexOfFirst { old ->
                (url.isNotBlank() && old.url.isNotBlank() && old.url == url) ||
                    old.name.equals(name, ignoreCase = true)
            }
            if (index >= 0) {
                val old = current[index]
                if (old.url.isBlank() && url.isNotBlank()) current[index] = old.copy(url = url)
            } else {
                current.add(FbGroup(UUID.randomUUID().toString(), name, url))
                added++
            }
        }
        saveGroups(context, current)
        return added
    }

    fun remove(context: Context, ids: Set<String>) {
        saveGroups(context, all(context).filterNot { it.id in ids })
        val lists = lists(context)
        lists.forEach { (name, members) -> lists[name] = members.filterNot { it in ids }.toSet() }
        saveLists(context, lists)
    }

    fun canonicalUrl(raw: String): String {
        val token = Regex("https?://(?:www\\.|m\\.|web\\.)?facebook\\.com/groups/[^\\s,;|]+", RegexOption.IGNORE_CASE)
            .find(raw.trim())?.value ?: return ""
        val path = token.substringAfter("/groups/").substringBefore('?').trimEnd('/').substringBefore('/')
        if (path.isBlank() || path.startsWith("joins") || path.startsWith("feed")) return ""
        return "https://www.facebook.com/groups/$path/"
    }

    fun importLines(context: Context, input: String): Int {
        val groups = input.lines().mapNotNull { line ->
            val url = canonicalUrl(line)
            if (url.isBlank()) null else {
                val namePart = line.substringBefore("http").trim(' ', '|', '-', '–', ':', '\t')
                (namePart.ifBlank { url.substringAfter("/groups/").substringBefore('/') }) to url
            }
        }
        return add(context, groups)
    }

    private fun saveGroups(context: Context, groups: List<FbGroup>) {
        val arr = JSONArray()
        groups.forEach { g -> arr.put(JSONObject().put("id", g.id).put("name", g.name).put("url", g.url)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(GROUPS, arr.toString()).apply()
    }

    fun lists(context: Context): MutableMap<String, Set<String>> = try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LISTS, "{}") ?: "{}"
        val obj = JSONObject(raw)
        val result = linkedMapOf<String, Set<String>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val ids = obj.getJSONArray(key)
            result[key] = (0 until ids.length()).map { ids.getString(it) }.toSet()
        }
        result
    } catch (_: Exception) { linkedMapOf() }

    private fun saveLists(context: Context, lists: Map<String, Set<String>>) {
        val obj = JSONObject()
        lists.forEach { (name, ids) -> obj.put(name, JSONArray().apply { ids.forEach { put(it) } }) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LISTS, obj.toString()).apply()
    }

    fun saveList(context: Context, name: String, ids: Set<String>) {
        val clean = name.trim().take(70)
        if (clean.isBlank()) return
        val lists = lists(context)
        lists[clean] = ids
        saveLists(context, lists)
    }

    fun deleteList(context: Context, name: String) {
        val lists = lists(context)
        lists.remove(name)
        saveLists(context, lists)
    }
}
