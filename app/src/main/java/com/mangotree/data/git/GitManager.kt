package com.mangotree.data.git

import android.content.Context
import android.media.MediaScannerConnection
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

/**
 * [context] is required so we can rescan a repo directory with MediaScannerConnection
 * after JGit writes to it. JGit performs plain java.io.File writes on the raw
 * filesystem path resolved from the user's picked SAF tree Uri (see UriToFile),
 * bypassing MediaStore entirely. Without an explicit rescan, apps whose file
 * pickers read from the MediaStore index (rather than doing a live filesystem
 * read) won't see files that were added, modified, or deleted by clone/pull/
 * checkout until an unrelated scan happens to catch up (or the device reboots).
 */
class GitManager(private val context: Context) {

    private fun creds(token: String) = UsernamePasswordCredentialsProvider(token, "")

    /**
     * Recursively rescans [dir] so MediaStore (and anything backed by it, like
     * other apps' file pickers) picks up files that JGit just created, modified,
     * or deleted directly on disk.
     */
    private fun rescan(dir: File) {
        if (!dir.exists()) return
        val paths = mutableListOf<String>()
        // Include the directory itself so deletions within it are also noticed.
        collectPaths(dir, paths)
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        } catch (_: Exception) {
            // Scanning is a best-effort visibility fix; never fail the git
            // operation itself because of it.
        }
    }

    private fun collectPaths(dir: File, out: MutableList<String>) {
        out.add(dir.absolutePath)
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                // Skip .git internals — they're never meant to be visible to
                // other apps and walking the whole object store is wasted work.
                if (child.name == ".git") continue
                collectPaths(child, out)
            } else {
                out.add(child.absolutePath)
            }
        }
    }

    suspend fun clone(remoteUrl: String, localDir: File, token: String): GitResult =
        withContext(Dispatchers.IO) {
            try {
                Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(localDir)
                    .setCredentialsProvider(creds(token))
                    .call()
                rescan(localDir)
                GitResult.Success
            } catch (e: Exception) {
                GitResult.Error(e.message ?: "Clone failed")
            }
        }

    suspend fun defaultBranch(localDir: File): String = withContext(Dispatchers.IO) {
        try {
            // remote.origin.HEAD is a ref file, not a config entry — git never
            // populates it as a config key, so reading it here always misses.
            // The branch JGit actually checked out during clone is the real
            // answer, and is already reflected in the current HEAD.
            Git.open(localDir).repository.branch ?: "main"
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
            rescan(localDir)
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
                rescan(localDir)
                GitResult.Success
            } catch (e: Exception) {
                GitResult.Error(e.message ?: "Force pull failed")
            }
        }

    suspend fun push(localDir: File, token: String): GitResult = withContext(Dispatchers.IO) {
        try {
            val git = Git.open(localDir)
            val branch = git.repository.branch

            val results = git.push()
                .setCredentialsProvider(creds(token))
                .setRemote("origin")
                .add("refs/heads/$branch:refs/heads/$branch")  // explicit refspec
                .call()

            for (pushResult in results) {
                for (update in pushResult.remoteUpdates) {
                    val status = update.status
                    if (status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK &&
                        status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE
                    ) {
                        // Prefer the server message; fall back to status name
                        val detail = update.message?.takeIf { it.isNotBlank() } ?: status.name
                        return@withContext when (status) {
                            org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ->
                                GitResult.Error("Push rejected: remote has changes you don't have locally. Pull first, then push again.")
                            org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED ->
                                GitResult.Error("Push rejected: remote ref changed during push. Try again.")
                            else ->
                                GitResult.Error("Push rejected ($status): $detail")
                        }
                    }
                }
            }
            // Push doesn't touch the working tree, so no rescan needed here.
            GitResult.Success
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
            files.forEach { path ->
                if (File(localDir, path).exists()) {
                    git.add().addFilepattern(path).call()
                } else {
                    // AddCommand silently ignores paths that no longer exist on
                    // disk, so a locally-deleted file never gets staged as a
                    // removal. RmCommand stages the deletion in the index instead.
                    git.rm().addFilepattern(path).call()
                }
            }
            git.commit()
                .setMessage(message.ifBlank { "Update" })
                .setAuthor(authorName, authorEmail)
                .setCommitter(authorName, authorEmail)
                .call()
            // Commit only touches the .git index/objects, not the working tree,
            // so there's nothing new for other apps' file pickers to see.
            GitResult.Success
        } catch (e: Exception) {
            GitResult.Error(e.message ?: "Commit failed")
        }
    }

    suspend fun discardFile(localDir: File, path: String): GitResult = withContext(Dispatchers.IO) {
        try {
            val git = Git.open(localDir)
            val status = git.status().call()
            val result = when {
                status.untracked.contains(path) -> {
                    File(localDir, path).delete()
                    GitResult.Success
                }
                else -> {
                    git.checkout().addPath(path).call()
                    GitResult.Success
                }
            }
            rescan(localDir)
            result
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
                rescan(localDir)
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
