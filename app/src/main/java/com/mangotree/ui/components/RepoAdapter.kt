package com.mangotree.ui.components

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mangotree.data.git.RepoEntry
import com.mangotree.databinding.ItemRepoBinding

class RepoAdapter(
    private val onSync: (RepoEntry) -> Unit,
    private val onBranch: (RepoEntry) -> Unit,
    private val onRemove: (RepoEntry) -> Unit
) : ListAdapter<RepoEntry, RepoAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val binding: ItemRepoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(repo: RepoEntry) {
            binding.repoName.text = repo.name
            binding.repoBranch.text = repo.currentBranch
            binding.repoUrl.text = repo.remoteUrl
            binding.syncButton.setOnClickListener { onSync(repo) }
            binding.branchButton.setOnClickListener { onBranch(repo) }
            binding.removeButton.setOnClickListener { onRemove(repo) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemRepoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RepoEntry>() {
            override fun areItemsTheSame(a: RepoEntry, b: RepoEntry) = a.localUri == b.localUri
            override fun areContentsTheSame(a: RepoEntry, b: RepoEntry) = a == b
        }
    }
}
