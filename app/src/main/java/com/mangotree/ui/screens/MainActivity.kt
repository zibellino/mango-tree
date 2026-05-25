package com.mangotree.ui.screens

import android.app.Activity
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.mangotree.R
import com.mangotree.data.auth.GitHubAuthManager
import com.mangotree.data.git.GitResult
import com.mangotree.data.git.RepoEntry
import com.mangotree.databinding.ActivityMainBinding
import com.mangotree.ui.components.RepoAdapter
import com.mangotree.util.UriToFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var authManager: GitHubAuthManager
    private lateinit var adapter: RepoAdapter

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { handleFolderPicked(it) }
    }

    private val oauthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val response = AuthorizationResponse.fromIntent(data)
            val ex = AuthorizationException.fromIntent(data)
            if (response != null) {
                exchangeToken(response)
            } else {
                Toast.makeText(this, "Auth failed: ${ex?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        requestStoragePermission()
        authManager = GitHubAuthManager(this)

        setupRecyclerView()
        observeViewModel()

        binding.fab.setOnClickListener {
            if (!viewModel.tokenStore.isLoggedIn()) {
                showLoginRequired()
            } else {
                showAddRepoDialog()
            }
        }

        if (!viewModel.tokenStore.isLoggedIn()) {
            showLoginBanner()
        }
    }

    private fun setupRecyclerView() {
        adapter = RepoAdapter(
            onSync = { repo -> viewModel.sync(repo) },
            onBranch = { repo -> showBranchDialog(repo) },
            onRemove = { repo ->
                AlertDialog.Builder(this)
                    .setTitle("Remove repo")
                    .setMessage("Remove ${repo.name} from MangoTree? (local files are kept)")
                    .setPositiveButton("Remove") { _, _ ->
                        viewModel.removeRepo(repo.localUri)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun observeViewModel() {
        viewModel.repos.observe(this) { repos ->
            adapter.submitList(repos)
            binding.emptyText.visibility = if (repos.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.uiState.observe(this) { state ->
            when (state) {
                is UiState.Loading -> binding.progressBar.visibility = View.VISIBLE
                is UiState.Message -> {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this, state.text,
                        if (state.isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                }
                is UiState.ConflictDetected -> {
                    binding.progressBar.visibility = View.GONE
                    showConflictDialog()
                }
                is UiState.Idle -> binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun showLoginBanner() {
        binding.loginBanner.visibility = View.VISIBLE
        binding.loginBanner.setOnClickListener { startOAuth() }
    }

    private fun showLoginRequired() {
        AlertDialog.Builder(this)
            .setTitle("Login required")
            .setMessage("Connect your GitHub account first.")
            .setPositiveButton("Login with GitHub") { _, _ -> startOAuth() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startOAuth() {
        val prefs = getSharedPreferences("oauth_config", MODE_PRIVATE)
        val clientId = prefs.getString("client_id", "") ?: ""
        if (clientId.isBlank()) {
            showOAuthConfigDialog()
            return
        }
        oauthLauncher.launch(authManager.buildAuthIntent(clientId))
    }

    private fun showOAuthConfigDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_oauth_config, null)
        val clientIdInput = view.findViewById<android.widget.EditText>(R.id.clientIdInput)
        val clientSecretInput = view.findViewById<android.widget.EditText>(R.id.clientSecretInput)

        AlertDialog.Builder(this)
            .setTitle("GitHub OAuth App")
            .setMessage("Enter your GitHub OAuth app credentials.\n\nRedirect URI to use:\ncom.mangotree://oauth")
            .setView(view)
            .setPositiveButton("Save & Login") { _, _ ->
                val clientId = clientIdInput.text.toString().trim()
                val clientSecret = clientSecretInput.text.toString().trim()
                if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
                    getSharedPreferences("oauth_config", MODE_PRIVATE).edit()
                        .putString("client_id", clientId)
                        .putString("client_secret", clientSecret)
                        .apply()
                    oauthLauncher.launch(authManager.buildAuthIntent(clientId))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exchangeToken(response: AuthorizationResponse) {
        val prefs = getSharedPreferences("oauth_config", MODE_PRIVATE)
        val clientId = prefs.getString("client_id", "") ?: ""
        val clientSecret = prefs.getString("client_secret", "") ?: ""
        authManager.exchangeCodeForToken(response, clientId, clientSecret,
            onSuccess = { token ->
                viewModel.tokenStore.saveToken(token)
                binding.loginBanner.visibility = View.GONE
                Toast.makeText(this, "Logged in!", Toast.LENGTH_SHORT).show()
            },
            onError = { err ->
                Toast.makeText(this, "Login failed: $err", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun showAddRepoDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_repo, null)
        val nameInput = view.findViewById<android.widget.EditText>(R.id.repoNameInput)
        val urlInput = view.findViewById<android.widget.EditText>(R.id.repoUrlInput)

        AlertDialog.Builder(this)
            .setTitle("Add repository")
            .setView(view)
            .setPositiveButton("Pick folder") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isNotBlank() && url.isNotBlank()) {
                    pendingName = name
                    pendingUrl = url
                    folderPickerLauncher.launch(null)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var pendingName = ""
    private var pendingUrl = ""

    private fun handleFolderPicked(uri: Uri) {
        // Persist permission across reboots
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val dir = UriToFile.fromUri(this, uri)
        if (dir == null) {
            Toast.makeText(this, "Cannot access this folder", Toast.LENGTH_LONG).show()
            return
        }

        val token = viewModel.tokenStore.getToken() ?: return

        if (viewModel.gitManager.isGitRepo(dir)) {
            // Already a git repo, just add it
            viewModel.repoStore.add(RepoEntry(pendingName, uri.toString(), pendingUrl))
            viewModel.refreshRepos()
        } else {
            // Clone into it
            Toast.makeText(this, "Cloning...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val result = viewModel.gitManager.clone(pendingUrl, dir, token)
                if (result is GitResult.Success) {
                    viewModel.repoStore.add(RepoEntry(pendingName, uri.toString(), pendingUrl))
                    viewModel.refreshRepos()
                    Toast.makeText(this@MainActivity, "Cloned!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity,
                        (result as GitResult.Error).message,
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showBranchDialog(repo: RepoEntry) {
        viewModel.listBranches(repo) { branches ->
            runOnUiThread {
                val arr = branches.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Switch branch")
                    .setItems(arr) { _, i ->
                        viewModel.switchBranch(repo, arr[i])
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun showConflictDialog() {
        // Find the conflicting repo
        val repo = viewModel.repos.value?.firstOrNull {
            UriToFile.fromUri(this, Uri.parse(it.localUri)) == viewModel.conflictRepoDir
        } ?: return

        AlertDialog.Builder(this)
            .setTitle("Merge conflict")
            .setMessage("There are conflicts that can't be auto-resolved.")
            .setPositiveButton("Discard local & force pull") { _, _ ->
                viewModel.forcePull(repo)
            }
            .setNegativeButton("Cancel sync") { _, _ ->
                viewModel.uiState.value = UiState.Idle
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                viewModel.tokenStore.clearToken()
                showLoginBanner()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        authManager.dispose()
    }
}

