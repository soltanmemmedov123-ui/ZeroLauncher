package com.movtery.zalithlauncher.ui.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.ItemLocalRendererViewBinding
import com.movtery.zalithlauncher.plugins.PluginLoader
import com.movtery.zalithlauncher.plugins.renderer.LocalRendererPlugin
import com.movtery.zalithlauncher.plugins.renderer.RendererPluginManager
import com.movtery.zalithlauncher.renderer.Renderers
import org.apache.commons.io.FileUtils

/**
 * Dialog used to manage locally installed renderer plugins.
 *
 * This dialog forces a plugin refresh when opened so newly added plugins
 * are visible immediately without requiring the user to fully restart the app.
 */
class LocalRendererPluginDialog(
    private val context: Context
) : AbstractSelectDialog(context) {

    override fun initDialog(recyclerView: RecyclerView) {
        setTitleText(R.string.setting_renderer_local_manage)

        // Refresh plugin state every time the dialog opens so newly installed
        // renderer plugins are detected immediately.
        Renderers.init(true)
        PluginLoader.loadAllPlugins(context, true)

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = LocalRendererPluginAdapter {
            dismiss()
        }
    }

    private class LocalRendererPluginAdapter(
        private val onNoPlugin: () -> Unit
    ) : RecyclerView.Adapter<LocalRendererPluginAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemLocalRendererViewBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(RendererPluginManager.getAllLocalRendererList()[position])
        }

        override fun getItemCount(): Int {
            return RendererPluginManager.getAllLocalRendererList().size
        }

        inner class ViewHolder(
            private val binding: ItemLocalRendererViewBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            @SuppressLint("NotifyDataSetChanged")
            fun bind(renderer: LocalRendererPlugin) {
                binding.rendererIdentifier.text = renderer.uniqueIdentifier
                binding.rendererName.text = renderer.displayName
                binding.rendererId.text = renderer.id

                binding.delete.setOnClickListener {
                    TipDialog.Builder(binding.root.context)
                        .setTitle(R.string.generic_warning)
                        .setMessage(R.string.setting_renderer_local_delete)
                        .setWarning()
                        .setConfirmClickListener {
                            FileUtils.deleteQuietly(renderer.folderPath)

                            // Rebuild renderer state after deletion so the list and
                            // renderer registry stay in sync immediately.
                            Renderers.init(true)
                            PluginLoader.loadAllPlugins(binding.root.context, true)

                            if (RendererPluginManager.getAllLocalRendererList().isNotEmpty()) {
                                notifyDataSetChanged()
                            } else {
                                onNoPlugin()
                            }
                        }
                        .showDialog()
                }
            }
        }
    }
}
