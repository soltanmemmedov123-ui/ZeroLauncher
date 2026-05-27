package com.movtery.zalithlauncher.ui.subassembly.customprofilepath

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.recyclerview.widget.RecyclerView
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.ItemProfilePathBinding
import com.movtery.zalithlauncher.databinding.ViewPathManagerBinding
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathManager
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathManager.setCurrentPathId
import com.movtery.zalithlauncher.feature.version.VersionsManager
import com.movtery.zalithlauncher.setting.AllSettings.Companion.launcherProfile
import com.movtery.zalithlauncher.ui.dialog.EditTextDialog
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import com.movtery.zalithlauncher.ui.fragment.FilesFragment
import com.movtery.zalithlauncher.ui.fragment.FragmentWithAnim
import com.movtery.zalithlauncher.utils.StoragePermissionsUtils
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.path.PathManager
import java.io.File

class ProfilePathAdapter(
    private val fragment: FragmentWithAnim,
    private val recyclerView: RecyclerView
) : RecyclerView.Adapter<ProfilePathAdapter.ViewHolder>() {

    companion object {
        private const val DEFAULT_PROFILE_ID = "default"
    }

    private val items: MutableList<ProfileItem> = mutableListOf()

    // If storage permission is missing, fall back to the default path.
    private var currentId: String =
        if (StoragePermissionsUtils.hasCachedPermission()) {
            launcherProfile.getValue()
        } else {
            DEFAULT_PROFILE_ID
        }

    private val managerPopupWindow: PopupWindow = PopupWindow().apply {
        isFocusable = true
        isOutsideTouchable = true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ItemProfilePathBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(data: List<ProfileItem>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
        recyclerView.scheduleLayoutAnimation()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshData() {
        ProfilePathManager.save(items)
        ProfilePathManager.refreshPath()
        notifyDataSetChanged()
        recyclerView.scheduleLayoutAnimation()
    }

    fun closePopupWindow() {
        managerPopupWindow.dismiss()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setPathId(id: String) {
        currentId = id
        setCurrentPathId(id)
        notifyDataSetChanged()
    }

    inner class ViewHolder(
        val binding: ItemProfilePathBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(profileItem: ProfileItem, position: Int) {
            binding.radioButton.tag = profileItem.id
            binding.radioButton.isChecked = currentId == profileItem.id
            binding.title.text = profileItem.title
            binding.path.text = profileItem.path
            binding.path.isSelected = true

            val selectClickListener = View.OnClickListener {
                if (!VersionsManager.canRefresh() || currentId == profileItem.id) {
                    return@OnClickListener
                }

                StoragePermissionsUtils.ensurePermissions(
                    activity = fragment.requireActivity(),
                    title = R.string.profiles_path_title,
                    permissionResult = object : StoragePermissionsUtils.PermissionResult {
                        override fun onGranted() {
                            setPathId(profileItem.id)
                        }

                        override fun onCancelled() = Unit
                    }
                )
            }

            binding.root.setOnClickListener(selectClickListener)
            binding.radioButton.setOnClickListener(selectClickListener)

            binding.operate.setOnClickListener {
                showPopupWindow(
                    anchorView = binding.root,
                    isDefault = profileItem.id == DEFAULT_PROFILE_ID,
                    profileItem = profileItem,
                    itemIndex = position
                )
            }
        }

        private fun showPopupWindow(
            anchorView: View,
            isDefault: Boolean,
            profileItem: ProfileItem,
            itemIndex: Int
        ) {
            val context = anchorView.context

            val popupBinding = ViewPathManagerBinding.inflate(LayoutInflater.from(context)).apply {
                val actionClickListener = View.OnClickListener { clickedView ->
                    when (clickedView) {
                        gotoView -> {
                            /*val bundle = Bundle().apply {
                                putString(
                                    FilesFragment.BUNDLE_LOCK_PATH,
                                    Environment.getExternalStorageDirectory().absolutePath
                                )
                                putString(FilesFragment.BUNDLE_LIST_PATH, profileItem.path)
                            }*/
                            val storageRoot = PathManager.findContainingStorageRoot(context, profileItem.path)
                                ?: PathManager.getPrimaryStorageRoot(context)

                            val bundle = Bundle().apply {
                                putString(FilesFragment.BUNDLE_LOCK_PATH, storageRoot.absolutePath)
                                putString(FilesFragment.BUNDLE_LIST_PATH, profileItem.path)
                            }

                            ZHTools.swapFragmentWithAnim(
                                fragment,
                                FilesFragment::class.java,
                                FilesFragment.TAG,
                                bundle
                            )
                        }

                        rename -> {
                            EditTextDialog.Builder(context)
                                .setTitle(R.string.generic_rename)
                                .setEditText(profileItem.title)
                                .setAsRequired()
                                .setConfirmListener { editBox, _ ->
                                    val newTitle = editBox.text.toString().trim()
                                    if (newTitle.isEmpty()) {
                                        editBox.error = context.getString(R.string.generic_error_field_empty)
                                        return@setConfirmListener false
                                    }

                                    items[itemIndex].title = newTitle
                                    refreshData()
                                    true
                                }
                                .showDialog()
                        }

                        delete -> {
                            TipDialog.Builder(context)
                                .setTitle(context.getString(R.string.profiles_path_delete_title))
                                .setMessage(R.string.profiles_path_delete_message)
                                .setCancelable(false)
                                .setConfirmClickListener {
                                    if (currentId == profileItem.id) {
                                        // If the currently selected path is deleted,
                                        // automatically switch back to the default path.
                                        setPathId(DEFAULT_PROFILE_ID)
                                    }

                                    items.removeAt(itemIndex)
                                    refreshData()
                                }
                                .showDialog()
                        }
                    }

                    managerPopupWindow.dismiss()
                }

                gotoView.setOnClickListener(actionClickListener)
                rename.setOnClickListener(actionClickListener)
                delete.setOnClickListener(actionClickListener)

                if (isDefault) {
                    renameLayout.visibility = View.GONE
                    deleteLayout.visibility = View.GONE
                }
            }

            managerPopupWindow.apply {
                popupBinding.root.measure(0, 0)
                contentView = popupBinding.root
                width = popupBinding.root.measuredWidth
                height = popupBinding.root.measuredHeight
                showAsDropDown(anchorView, anchorView.measuredWidth, 0)
            }
        }
    }
}