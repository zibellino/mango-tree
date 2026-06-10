package com.mangotree.data.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

sealed class GitResult {
    object Success : GitResult()
    data class Error(val message: String) : GitResult()
    object ConflictDetected : GitResult()
}

data class ChangedFile(
    val path: String,
    val status: String  // "Modified", "Added", "Deleted", "Untracked"
)

class GitManager {

    private fun creds(token: String) = UsernamePasswordCredentialsProvider(token, "")

    suspend fun clone(remoteUrl: String, localDir: File, token: String): GitResult =
        withContext(Dispatchers.IO) {
            try {
                Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(localDir)
                    .setCredentialsProvider(creds(token))
                    .call()
                GitResult.Success
            } catch (e: Exception) {
                GitResult.Error(e.message ?: "Clone failed")
            }
        }

    suspend fun defaultBranch(localDir: File): String = withContext(Dispatchers.IO) {
        try {
            Git.open(localDir).repository.config
                .getString("remote", "origin", "HEAD")
                ?.removePrefix("refs/heads/")
                ?: "main"
            
        } catch (e: Exception) {
            "main"
        }
    }

    suspend fun pull(localDir: File, token: String): GitResult = withContext(Dispatchers.IO) {
        try {
            val git = Git.open(localDir)
            val result = git.pull()
                .setCredentialsProvider(creds(token))
                .setRebase(true)
                .call()
            if (result.isSuccessful) syncLocalBranchesToRemote(git, token)
            when {
                result.isSuccessful -> GitResult.Success
                result.rebaseResult?.status == RebaseResult.Status.STOPPED ->
                    GitResult.ConflictDetected
                else -> GitResult.Error("Pull failed: ${result.rebaseResult?.status}")
            }
        } catch (e: Exception) {
            GitResult.Error(e.message ?: "Pull failed")
        }
    }

    private fun syncLocalBranchesToRemote(git: Git, token: String) {
        git.fetch().setRemote("origin").setCredentialsProvider(creds(token)).setRemoveDeletedRefs(true).call()

        val remoteBranches = git.branchList()
            .setListMode(ListBranchCommand.ListMode.REMOTE)
            .call()
            .map { it.name.removePrefix("refs/remotes/origin/") }
            .filter { it != "HEAD" }
            .toSet()

        val currentBranch = git.repository.branch

        // switch off orphaned current branch first so it can be deleted below
        if (currentBranch !in remoteBranches) {
            val default = git.repository.config
                .getString("remote", "origin", "HEAD")
                ?.removePrefix("refs/heads/")
            if (default != null) {
                try { git.checkout().setName(default).call() }
                catch (_: Exception) {}
            }
        }

        // now safe to delete any local branch with no remote counterpart
        git.branchList().call()
            .map { it.name.removePrefix("refs/heads/") }
            .filter { it !in remoteBranches }
            .filter { it != git.repository.branch }
            .forEach { branch ->
                git.branchDelete().setBranchNames("refs/heads/$branch").setForce(true).call()
            }

        // create local tracking branches for any new remotes
        val localBranches = git.branchList().call()
            .map { it.name.removePrefix("refs/heads/") }
            .toSet()
        remoteBranches
            .filter { it !in localBranches }
            .forEach { branch ->
                try {
                    git.branchCreate()
                        .setName(branch)
                        .setStartPoint("origin/$branch")
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                        .call()
                } catch (_: Exception) {}
            }
    }

    suspend fun forcePull(localDir: File, token: String, branch: String): GitResult =
        withContext(Dispatchers.IO) {
            try {
                val git = Git.open(localDir)
                try {
                    git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
                } catch (_: Exception) {}
                git.fetch().setCredentialsProvider(creds(token)).call()
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/$branch").call()
                GitResult.Success
            } catch (e: Exception) {
                GitResult.Error(e.message ?: "Force pull failed")
            }
        }

    suspend fun push(localDir: File, token: String): GitResult = withContext(Dispatchers.IO) {
        try {
            val git = Git.open(localDir)
            val results = git.push().setCredentialsProvider(creds(token)).call()
            val error = results.mapNotNull {
                it.remoteUpdates.firstOrNull { u ->
                    u.status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK &&
                    u.status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE
                }
            }.firstOrNull()
            if (error != null) GitResult.Error("Push rejected: ${error.status}")
            else GitResult.Success
        } catch (e: Exception) {
            GitResult.Error(e.message ?: "Push failed")
        }
    }

    suspend fun getChangedFiles(localDir: File): List<ChangedFile> = withContext(Dispatchers.IO) {
        try {
            val git = Git.open(localDir)
            val status = git.status().call()
            val files = mutableListOf<ChangedFile>()
            status.modified.forEach { files.add(ChangedFile(it, "Modified")) }
            status.added.forEach { files.add(ChangedFile(it, "Added")) }
            status.removed.forEach { files.add(ChangedFile(it, "Deleted")) }
            status.untracked.forEach { files.add(ChangedFile(it, "Untracked")) }
            status.missing.forEach { files.add(ChangedFile(it, "Missing")) }
            files.sortedBy { it.path }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun commit(
        localDir: File,
        files: List<String>,
        message: String,
        authorName: String,
        authorEmail: String
    ): GitResult = withContext(Dispatchers.IO) {
        try {
            val git = Git.open(localDir)
            val addCmd = git.add()
            files.forEach { addCmd.addFilepattern(it) }
            addCmd.call()
            git.commit()
                .setMessage(message.ifBlank { "Update" })
                .setAuthor(authorName, authorEmail)
                .call()
            GitResult.Success
        } catch (e: Exception) {
            GitResult.Error(e.message ?: "Commit failed")
        }
    }

    suspend fun discardFile(localDir: File, path: String): GitResult = withContext(Dispatchers.IO) {
        try {
            val git = Git.open(localDir)
            val status = git.status().call()
            when {
                status.untracked.contains(path) -> {
                    File(localDir, path).delete()
                    GitResult.Success
                }
                else -> {
                    git.checkout().addPath(path).call()
                    GitResult.Success
                }
            }
        } catch (e: Exception) {
            GitResult.Error(e.message ?: "Discard failed")
        }
    }

    suspend fun listBranches(localDir: File): List<String> = withContext(Dispatchers.IO) {
        try {
            Git.open(localDir).branchList().call()
                .map { it.name.removePrefix("refs/heads/") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun switchBranch(localDir: File, branch: String): GitResult =
        withContext(Dispatchers.IO) {
            try {
                val git = Git.open(localDir)
                val exists = git.branchList().call()
                    .any { it.name.removePrefix("refs/heads/") == branch }
                if (exists) {
                    git.checkout().setName(branch).call()
                } else {
                    git.checkout().setCreateBranch(true).setName(branch)
                        .setStartPoint("origin/$branch").call()
                }
                GitResult.Success
            } catch (e: Exception) {
                GitResult.Error(e.message ?: "Branch switch failed")
            }
        }

    suspend fun currentBranch(localDir: File): String = withContext(Dispatchers.IO) {
        try { Git.open(localDir).repository.branch } catch (e: Exception) { "unknown" }
    }

    fun isGitRepo(dir: File): Boolean = File(dir, ".git").exists()
}
