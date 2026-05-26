package com.mangotree.data.git

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RepoEntry(
    val name: String,
    val localUri: String,
    val remoteUrl: String,
    val currentBranch: String = "main"
) {
    fun toJson(): String = JSONObject().apply {
        put("name", name)
        put("localUri", localUri)
        put("remoteUrl", remoteUrl)
        put("currentBranch", currentBranch)
    }.toString()

    companion object {
        fun fromJson(json: String): RepoEntry {
            val obj = JSONObject(json)
            return RepoEntry(
                name = obj.getString("name"),
                localUri = obj.getString("localUri"),
                remoteUrl = obj.getString("remoteUrl"),
                currentBranch = obj.optString("currentBranch", "main")
            )
        }
    }
}

class RepoStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("repos", Context.MODE_PRIVATE)

    fun getAll(): List<RepoEntry> {
        val json = prefs.getString(KEY_REPOS, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { RepoEntry.fromJson(arr.getString(it)) }
    }

    fun save(repos: List<RepoEntry>) {
        val arr = JSONArray()
        repos.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_REPOS, arr.toString()).apply()
    }

    fun add(repo: RepoEntry) = save(getAll() + repo)

    fun remove(localUri: String) = save(getAll().filter { it.localUri != localUri })

    fun update(updated: RepoEntry) {
        save(getAll().map { if (it.localUri == updated.localUri) updated else it })
    }

    companion object {
        private const val KEY_REPOS = "repo_list"
    }
}
