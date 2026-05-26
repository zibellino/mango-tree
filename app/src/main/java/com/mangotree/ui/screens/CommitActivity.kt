package com.mangotree.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mangotree.R
import com.mangotree.data.git.ChangedFile
import com.mangotree.data.git.RepoEntry
import com.mangotree.databinding.ActivityCommitBinding

class CommitActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCommitBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var repo: RepoEntry
    private lateinit var adapter: ChangedFilesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommitBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Commit"

        val repoJson = intent.getStringExtra("repo") ?: return finish()
        repo = RepoEntry.fromJson(repoJson)

        adapter = ChangedFilesAdapter(
            onDiscard = { file ->
                AlertDialog.Builder(this)
                    .setTitle("Discard changes")
                    .setMessage("Discard changes to ${file.path}?")
                    .setPositiveButton("Discard") { _, _ ->
                        viewModel.discardFile(repo, file.path)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        binding.changedFilesList.layoutManager = LinearLayoutManager(this)
        binding.changedFilesList.adapter = adapter

        binding.commitButton.setOnClickListener {
            val selected = adapter.getCheckedFiles()
            if (selected.isEmpty()) {
                Toast.makeText(this, "No files selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val message = binding.commitMessageInput.text.toString().trim()
            viewModel.commit(repo, selected, message)
        }

        viewModel.changedFiles.observe(this) { files ->
            adapter.submitList(files)
            binding.emptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            binding.changedFilesList.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.uiState.observe(this) { state ->
            when (state) {
                is UiState.Message -> {
                    Toast.makeText(this, state.text,
                        if (state.isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                    if (!state.isError) finish()
                }
                else -> {}
            }
        }

        viewModel.loadChangedFiles(repo)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class ChangedFilesAdapter(
    private val onDiscard: (ChangedFile) -> Unit
) : RecyclerView.Adapter<ChangedFilesAdapter.ViewHolder>() {

    private var files: List<ChangedFile> = emptyList()
    private val checked = mutableSetOf<String>()

    fun submitList(newFiles: List<ChangedFile>) {
        files = newFiles
        checked.clear()
        checked.addAll(newFiles.map { it.path })
        notifyDataSetChanged()
    }

    fun getCheckedFiles(): List<String> = checked.toList()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox = view.findViewById(R.id.fileCheckbox)
        val status: TextView = view.findViewById(R.id.fileStatus)
        val discard: ImageButton = view.findViewById(R.id.discardButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_changed_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.checkbox.text = file.path
        holder.checkbox.isChecked = file.path in checked
        holder.status.text = file.status
        holder.status.setTextColor(statusColor(holder.status, file.status))
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) checked.add(file.path) else checked.remove(file.path)
        }
        holder.discard.setOnClickListener { onDiscard(file) }
    }

    override fun getItemCount() = files.size

    private fun statusColor(view: View, status: String): Int {
        val ctx = view.context
        return when (status) {
            "Added", "Untracked" -> ctx.getColor(android.R.color.holo_green_dark)
            "Deleted", "Missing" -> ctx.getColor(android.R.color.holo_red_dark)
            else -> ctx.getColor(android.R.color.holo_orange_dark)
        }
    }
}
