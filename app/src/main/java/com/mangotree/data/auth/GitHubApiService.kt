package com.mangotree.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GitHubRepo(
    val name: String,
    val fullName: String,
    val cloneUrl: String,
    val isPrivate: Boolean
)

data class GitHubUser(
    val login: String,
    val name: String?,
    val email: String?
)

class GitHubApiService {

    suspend fun fetchUserRepos(token: String): List<GitHubRepo> = withContext(Dispatchers.IO) {
        val repos = mutableListOf<GitHubRepo>()
        var page = 1
        while (true) {
            val url = URL("https://api.github.com/user/repos?per_page=100&page=$page&sort=updated")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val body = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(body)
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                repos.add(
                    GitHubRepo(
                        name = obj.getString("name"),
                        fullName = obj.getString("full_name"),
                        cloneUrl = obj.getString("clone_url"),
                        isPrivate = obj.getBoolean("private")
                    )
                )
            }
            page++
        }
        repos
    }

    // Fetches the authenticated user's profile (only needs the "repo" scope's
    // implicit read access; email will be null unless the user has a public
    // email set on their GitHub profile).
    suspend fun fetchAuthenticatedUser(token: String): GitHubUser = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/user")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        val body = conn.inputStream.bufferedReader().readText()
        val obj = JSONObject(body)
        GitHubUser(
            login = obj.getString("login"),
            name = obj.optString("name", null).takeUnless { it.isNullOrBlank() },
            email = obj.optString("email", null).takeUnless { it.isNullOrBlank() }
        )
    }
}
