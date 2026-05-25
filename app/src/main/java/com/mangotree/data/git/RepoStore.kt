package com.mangotree.data.git

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RepoEntry(
    val name: String,
    val localUri: String,   // persisted URI from folder picker
    val remoteUrl: String,
    val currentBranch: String = "main"
)

class RepoStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("repos", Context.MODE_PRIVATE)

    fun getAll(): List<RepoEntry> {
        val json = prefs.getString(KEY_REPOS, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            RepoEntry(
                name = obj.getString("name"),
                localUri = obj.getString("localUri"),
                remoteUrl = obj.getString("remoteUrl"),
                currentBranch = obj.optString("currentBranch", "main")
            )
        }
    }

    fun save(repos: List<RepoEntry>) {
        val arr = JSONArray()
        repos.forEach { repo ->
            arr.put(JSONObject().apply {
                put("name", repo.name)
                put("localUri", repo.localUri)
                put("remoteUrl", repo.remoteUrl)
                put("currentBranch", repo.currentBranch)
            })
        }
        prefs.edit().putString(KEY_REPOS, arr.toString()).apply()
    }

    fun add(repo: RepoEntry) = save(getAll() + repo)

    fun remove(localUri: String) = save(getAll().filter { it.localUri != localUri })

    fun update(updated: RepoEntry) {
        val list = getAll().map { if (it.localUri == updated.localUri) updated else it }
        save(list)
    }

    companion object {
        private const val KEY_REPOS = "repo_list"
    }
}
