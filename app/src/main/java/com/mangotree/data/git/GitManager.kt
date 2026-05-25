package com.mangotree.data.git

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

sealed class GitResult {
    object Success : GitResult()
    data class Error(val message: String) : GitResult()
    object ConflictDetected : GitResult()
}

class GitManager(private val context: Context) {

    private fun credentialsProvider(token: String) =
        UsernamePasswordCredentialsProvider(token, "")

    /**
     * Clone a remote repo into a user-picked directory.
     * The directory must already exist (from folder picker).
     */
    suspend fun clone(
        remoteUrl: String,
        localDir: File,
        token: String,
        onProgress: (String) -> Unit = {}
    ): GitResult = withContext(Dispatchers.IO) {
        try {
            onProgress("Cloning...")
            Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(localDir)
                .setCredentialsProvider(credentialsProvider(token))
                .call()
            GitResult.Success
        } catch (e: Exception) {
            GitResult.Error(e.message ?: "Clone failed")
        }
    }

    /**
     * Pull with rebase. On conflict, returns ConflictDetected.
     */
    suspend fun pull(localDir: File, token: String): GitResult = withContext(Dispatchers.IO) {
        try {
            val git = Git.open(localDir)
            val result = git.pull()
                .setCredentialsProvider(credentialsProvider(token))
                .setRebase(true)
                .call()

            when {
                result.isSuccessful -> GitResult.Success
                result.rebaseResult?.status == RebaseResult.Status.STOPPED ->
                    GitResult.ConflictDetected
                else -> GitResult.Error("Pull failed: ${result.mergeResult?.mergeStatus}")
            }
        } catch (e: Exception) {
            GitResult.Error(e.message ?: "Pull failed")
        }
    }

    /**
     * Force pull — abort rebase and reset hard to remote branch (discard local).
     */
    suspend fun forcePull(localDir: File, token: String, branch: String): GitResult =
        withContext(Dispatchers.IO) {
            try {
                val git = Git.open(localDir)
                // Abort any in-progress rebase
                try { git.rebase().setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.ABORT).call() } catch (_: Exception) {}
                // Fetch latest
                git.fetch().setCredentialsProvider(credentialsProvider(token)).call()
                // Reset hard to remote
                git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("origin/$branch")
                    .call()
                GitResult.Success
            } catch (e: Exception) {
                GitResult.Error(e.message ?: "Force pull failed")
            }
        }

    /**
     * Stage all changes and commit.
     */
    suspend fun commitAll(
        localDir: File,
        message: String,
        authorName: String,
        authorEmail: String
    ): GitResult = withContext(Dispatchers.IO) {
        try {
            val git = Git.open(localDir)
            git.add().addFilepattern(".").call()
            git.commit()
                .setMessage(message)
                .setAuthor(authorName, authorEmail)
                .call()
            GitResult.Success
        } catch (e: Exception) {
            GitResult.Error(e.message ?: "Commit failed")
        }
    }

    /**
     * Push to remote.
     */
    suspend fun push(localDir: File, token: String): GitResult = withContext(Dispatchers.IO) {
        try {
            val git = Git.open(localDir)
            val results = git.push()
                .setCredentialsProvider(credentialsProvider(token))
                .call()
            val error = results.mapNotNull { it.remoteUpdates.firstOrNull { u ->
                u.status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK &&
                u.status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE
            }}.firstOrNull()
            if (error != null) GitResult.Error("Push rejected: ${error.status}")
            else GitResult.Success
        } catch (e: Exception) {
            GitResult.Error(e.message ?: "Push failed")
        }
    }

    /**
     * List all local branches.
     */
    suspend fun listBranches(localDir: File): List<String> = withContext(Dispatchers.IO) {
        try {
            val git = Git.open(localDir)
            git.branchList().call().map { it.name.removePrefix("refs/heads/") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Switch to a branch.
     */
    suspend fun switchBranch(localDir: File, branch: String): GitResult =
        withContext(Dispatchers.IO) {
            try {
                val git = Git.open(localDir)
                val branches = git.branchList().call().map { it.name.removePrefix("refs/heads/") }
                if (branch in branches) {
                    git.checkout().setName(branch).call()
                } else {
                    // Create tracking branch from remote
                    git.checkout()
                        .setCreateBranch(true)
                        .setName(branch)
                        .setStartPoint("origin/$branch")
                        .call()
                }
                GitResult.Success
            } catch (e: Exception) {
                GitResult.Error(e.message ?: "Branch switch failed")
            }
        }

    /**
     * Get current branch name.
     */
    suspend fun currentBranch(localDir: File): String = withContext(Dispatchers.IO) {
        try {
            Git.open(localDir).repository.branch
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Sync = pull (rebase) then push.
     */
    suspend fun sync(localDir: File, token: String, branch: String): GitResult =
        withContext(Dispatchers.IO) {
            val pullResult = pull(localDir, token)
            if (pullResult != GitResult.Success) return@withContext pullResult
            push(localDir, token)
        }

    /**
     * Check if directory is a valid git repo.
     */
    fun isGitRepo(dir: File): Boolean = File(dir, ".git").exists()
}
