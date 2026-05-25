package com.mangotree.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mangotree.data.auth.TokenStore
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

    val repos = MutableLiveData<List<RepoEntry>>(emptyList())
    val uiState = MutableLiveData<UiState>(UiState.Idle)

    // Temp storage for conflict resolution context
    var conflictRepoDir: File? = null
    var conflictBranch: String = "main"

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

    fun sync(repo: RepoEntry) {
        val token = tokenStore.getToken() ?: return
        val dir = UriToFile.fromUri(getApplication(), Uri.parse(repo.localUri)) ?: run {
            uiState.value = UiState.Message("Cannot access directory", isError = true)
            return
        }
        uiState.value = UiState.Loading
        viewModelScope.launch {
            val result = gitManager.sync(dir, token, repo.currentBranch)
            handleResult(result, dir, repo.currentBranch)
        }
    }

    fun forcePull(repo: RepoEntry) {
        val token = tokenStore.getToken() ?: return
        val dir = conflictRepoDir ?: return
        uiState.value = UiState.Loading
        viewModelScope.launch {
            val result = gitManager.forcePull(dir, token, conflictBranch)
            handleResult(result, dir, conflictBranch)
        }
    }

    fun switchBranch(repo: RepoEntry, branch: String) {
        val dir = UriToFile.fromUri(getApplication(), Uri.parse(repo.localUri)) ?: return
        uiState.value = UiState.Loading
        viewModelScope.launch {
            val result = gitManager.switchBranch(dir, branch)
            if (result == GitResult.Success) {
                val updated = repo.copy(currentBranch = branch)
                repoStore.update(updated)
                refreshRepos()
                uiState.value = UiState.Message("Switched to $branch")
            } else {
                uiState.value = UiState.Message((result as GitResult.Error).message, isError = true)
            }
        }
    }

    fun listBranches(repo: RepoEntry, onResult: (List<String>) -> Unit) {
        val dir = UriToFile.fromUri(getApplication(), Uri.parse(repo.localUri)) ?: return
        viewModelScope.launch {
            onResult(gitManager.listBranches(dir))
        }
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
