package com.example.v2rayconfig.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.v2rayconfig.R
import com.example.v2rayconfig.model.ServerConfig

class ConfigListAdapter(
    private val items: MutableList<ServerConfig>,
    private val activeId: String?,
    private val latencies: Map<String, Long>,
    private val onClick: (ServerConfig) -> Unit,
    private val onDelete: (ServerConfig) -> Unit
) : RecyclerView.Adapter<ConfigListAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.textRemark)
        val subtitle: TextView = view.findViewById(R.id.textAddress)
        val status: TextView = view.findViewById(R.id.textStatus)
        val deleteBtn: View = view.findViewById(R.id.buttonDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_config, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.remark
        holder.subtitle.text = "${item.protocol}://${item.address}:${item.port}"

        val latency = latencies[item.id]
        val pingText = when {
            latency == null -> ""
            latency < 0 -> "timeout"
            else -> "${latency}ms"
        }
        val activeText = if (item.id == activeId) "● Active" else ""
        holder.status.text = listOf(activeText, pingText).filter { it.isNotBlank() }.joinToString(" · ")

        holder.itemView.setOnClickListener { onClick(item) }
        holder.deleteBtn.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = items.size
}
