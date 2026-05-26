package com.mangotree.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mangotree.R
import com.mangotree.data.auth.GitHubAuthManager
import com.mangotree.data.auth.GitHubRepo
import com.mangotree.data.git.GitResult
import com.mangotree.data.git.RepoEntry
import com.mangotree.databinding.ActivityMainBinding
import com.mangotree.ui.components.RepoAdapter
import com.mangotree.util.UriToFile
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var authManager: GitHubAuthManager
    private lateinit var adapter: RepoAdapter

    private var pendingGitHubRepo: GitHubRepo? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { handleFolderPicked(it) } }

    private val oauthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val response = AuthorizationResponse.fromIntent(data)
            val ex = AuthorizationException.fromIntent(data)
            if (response != null) exchangeToken(response)
            else Toast.makeText(this, "Auth failed: ${ex?.message}", Toast.LENGTH_LONG).show()
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
            if (!viewModel.tokenStore.isLoggedIn()) showLoginRequired()
            else showGitHubRepoPicker()
        }

        if (!viewModel.tokenStore.isLoggedIn()) showLoginBanner()
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

    private fun setupRecyclerView() {
        adapter = RepoAdapter(
            onPull = { repo -> viewModel.pull(repo) },
            onCommit = { repo ->
                val intent = Intent(this, CommitActivity::class.java)
                intent.putExtra("repo", repo.toJson())
                startActivity(intent)
            },
            onPush = { repo -> viewModel.push(repo) },
            onBranch = { repo -> showBranchDialog(repo) },
            onRemove = { repo ->
                AlertDialog.Builder(this)
                    .setTitle("Remove repo")
                    .setMessage("Remove ${repo.name} from MangoTree? (local files are kept)")
                    .setPositiveButton("Remove") { _, _ -> viewModel.removeRepo(repo.localUri) }
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
        if (clientId.isBlank()) { showOAuthConfigDialog(); return }
        oauthLauncher.launch(authManager.buildAuthIntent(clientId))
    }

    private fun showOAuthConfigDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_oauth_config, null)
        val clientIdInput = view.findViewById<android.widget.EditText>(R.id.clientIdInput)
        val clientSecretInput = view.findViewById<android.widget.EditText>(R.id.clientSecretInput)
        AlertDialog.Builder(this)
            .setTitle("GitHub OAuth App")
            .setMessage("Enter your GitHub OAuth app credentials.\n\nRedirect URI: com.mangotree://oauth")
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

    // GitHub repo picker — fetches list from API, shows searchable dialog
    private fun showGitHubRepoPicker() {
        viewModel.fetchGitHubRepos()
        viewModel.githubRepos.observe(this) { repos ->
            if (repos.isEmpty()) return@observe
            viewModel.githubRepos.removeObservers(this)

            val names = repos.map { "${it.name} ${if (it.isPrivate) "🔒" else ""}" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Select GitHub repo")
                .setItems(names) { _, i ->
                    pendingGitHubRepo = repos[i]
                    folderPickerLauncher.launch(null)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun handleFolderPicked(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val ghRepo = pendingGitHubRepo ?: return
        val dir = UriToFile.fromUri(this, uri) ?: run {
            Toast.makeText(this, "Cannot access this folder", Toast.LENGTH_LONG).show()
            return
        }
        val token = viewModel.tokenStore.getToken() ?: return

        if (viewModel.gitManager.isGitRepo(dir)) {
            viewModel.repoStore.add(RepoEntry(ghRepo.name, uri.toString(), ghRepo.cloneUrl))
            viewModel.refreshRepos()
        } else {
            Toast.makeText(this, "Cloning...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val result = viewModel.gitManager.clone(ghRepo.cloneUrl, dir, token)
                if (result is GitResult.Success) {
                    viewModel.repoStore.add(RepoEntry(ghRepo.name, uri.toString(), ghRepo.cloneUrl))
                    viewModel.refreshRepos()
                    Toast.makeText(this@MainActivity, "Cloned!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity,
                        (result as GitResult.Error).message, Toast.LENGTH_LONG).show()
                }
            }
        }
        pendingGitHubRepo = null
    }

    private fun showBranchDialog(repo: RepoEntry) {
        viewModel.listBranches(repo) { branches ->
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Switch branch")
                    .setItems(branches.toTypedArray()) { _, i ->
                        viewModel.switchBranch(repo, branches[i])
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun showConflictDialog() {
        val repo = viewModel.repos.value?.firstOrNull {
            UriToFile.fromUri(this, Uri.parse(it.localUri)) == viewModel.conflictRepoDir
        } ?: return
        AlertDialog.Builder(this)
            .setTitle("Merge conflict")
            .setMessage("Conflicts detected that can't be auto-resolved.")
            .setPositiveButton("Discard local & force pull") { _, _ -> viewModel.forcePull(repo) }
            .setNegativeButton("Cancel") { _, _ -> viewModel.uiState.value = UiState.Idle }
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
