package com.mangotree.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mangotree.data.auth.GitHubApiService
import com.mangotree.data.auth.GitHubRepo
import com.mangotree.data.auth.TokenStore
import com.mangotree.data.git.ChangedFile
import com.mangotree.data.git.GitManager
import com.mangotree.data.git.GitResult
import com.mangotree.data.git.RepoEntry
import com.mangotree.data.git.RepoStore
import com.mangotree.util.UriToFile
import kotlinx.coroutines.launch
import java.io.File

sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Message(val text: String, val isError: Boolean = false) : UiState()
    object ConflictDetected : UiState()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val tokenStore = TokenStore(app)
    val repoStore = RepoStore(app)
    val gitManager = GitManager(app)
    val apiService = GitHubApiService()

    val repos = MutableLiveData<List<RepoEntry>>(emptyList())
    val uiState = MutableLiveData<UiState>(UiState.Idle)
    val githubRepos = MutableLiveData<List<GitHubRepo>>(emptyList())
    val changedFiles = MutableLiveData<List<ChangedFile>>(emptyList())

    var conflictRepoDir: File? = null
    var conflictBranch: String = "main"

    // GitHub user info cache, used as the commit author.
    // Falls back to "MangoTree" / blank email if the profile can't be fetched.
    var githubUserName: String = "MangoTree"
    var githubUserEmail: String = ""
    private var githubUserInfoFetched: Boolean = false

    init {
        repos.value = repoStore.getAll()
    }

    fun refreshRepos() {
        repos.value = repoStore.getAll()
    }

    fun removeRepo(localUri: String) {
        repoStore.remove(localUri)
        refreshRepos()
    }

    fun pull(repo: RepoEntry) {
        val token = tokenStore.getToken() ?: return
        val dir = resolveDir(repo) ?: return
        uiState.value = UiState.Loading
        viewModelScope.launch {
            handleResult(gitManager.pull(dir, token), dir, repo.currentBranch)
        }
    }

    fun push(repo: RepoEntry) {
        val token = tokenStore.getToken() ?: return
        val dir = resolveDir(repo) ?: return
        uiState.value = UiState.Loading
        viewModelScope.launch {
            handleResult(gitManager.push(dir, token), dir, repo.currentBranch)
        }
    }

    fun loadChangedFiles(repo: RepoEntry) {
        val dir = resolveDir(repo) ?: return
        viewModelScope.launch {
            changedFiles.value = gitManager.getChangedFiles(dir)
        }
    }

    fun commit(repo: RepoEntry, files: List<String>, message: String) {
        val dir = resolveDir(repo) ?: return
        uiState.value = UiState.Loading
        viewModelScope.launch {
            ensureGitHubUserInfo()
            val result = gitManager.commit(dir, files, message, githubUserName, githubUserEmail)
            if (result == GitResult.Success) {
                changedFiles.value = emptyList()
            }
            handleResult(result, dir, repo.currentBranch)
        }
    }

    fun discardFile(repo: RepoEntry, path: String) {
        val dir = resolveDir(repo) ?: return
        viewModelScope.launch {
            gitManager.discardFile(dir, path)
            changedFiles.value = gitManager.getChangedFiles(dir)
        }
    }

    fun forcePull(repo: RepoEntry) {
        val token = tokenStore.getToken() ?: return
        val dir = conflictRepoDir ?: return
        uiState.value = UiState.Loading
        viewModelScope.launch {
            handleResult(gitManager.forcePull(dir, token, conflictBranch), dir, conflictBranch)
        }
    }

    fun switchBranch(repo: RepoEntry, branch: String) {
        val dir = resolveDir(repo) ?: return
        uiState.value = UiState.Loading
        viewModelScope.launch {
            val result = gitManager.switchBranch(dir, branch)
            if (result == GitResult.Success) {
                repoStore.update(repo.copy(currentBranch = branch))
                refreshRepos()
                uiState.value = UiState.Message("Switched to $branch")
            } else {
                uiState.value = UiState.Message((result as GitResult.Error).message, isError = true)
            }
        }
    }

    fun listBranches(repo: RepoEntry, onResult: (List<String>) -> Unit) {
        val dir = resolveDir(repo) ?: return
        viewModelScope.launch { onResult(gitManager.listBranches(dir)) }
    }

    fun fetchGitHubRepos() {
        val token = tokenStore.getToken() ?: return
        viewModelScope.launch {
            try {
                githubRepos.value = apiService.fetchUserRepos(token)
            } catch (e: Exception) {
                uiState.value = UiState.Message("Failed to load repos: ${e.message}", isError = true)
            }
        }
    }

    // Best-effort early fetch, called after login / on startup so the UI
    // can warm up the cache. commit() awaits ensureGitHubUserInfo() directly,
    // so it can never race with this.
    fun fetchGitHubUserInfo() {
        viewModelScope.launch { ensureGitHubUserInfo() }
    }

    // Fetches the GitHub user once and caches the result. Safe to call
    // repeatedly — a successful fetch is only performed once; a failed
    // fetch leaves the "MangoTree" / blank-email defaults and will retry
    // on the next call (e.g. the next commit).
    private suspend fun ensureGitHubUserInfo() {
        if (githubUserInfoFetched) return
        val token = tokenStore.getToken() ?: return
        try {
            val user = apiService.fetchAuthenticatedUser(token)
            githubUserName = user.name ?: user.login
            githubUserEmail = user.email ?: ""
            githubUserInfoFetched = true
        } catch (_: Exception) {
            githubUserName = "MangoTree"
            githubUserEmail = ""
        }
    }

    private fun resolveDir(repo: RepoEntry): File? {
        val dir = UriToFile.fromUri(getApplication(), Uri.parse(repo.localUri))
        if (dir == null) uiState.value = UiState.Message("Cannot access directory", isError = true)
        return dir
    }

    private fun handleResult(result: GitResult, dir: File, branch: String) {
        when (result) {
            is GitResult.Success -> uiState.value = UiState.Message("Done")
            is GitResult.Error -> uiState.value = UiState.Message(result.message, isError = true)
            is GitResult.ConflictDetected -> {
                conflictRepoDir = dir
                conflictBranch = branch
                uiState.value = UiState.ConflictDetected
            }
        }
    }
}
