package com.movtery.zalithlauncher.ui.fragment

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewbinding.ViewBinding
import com.angcyo.tablayout.DslTabLayout
import com.movtery.anim.AnimPlayer
import com.movtery.anim.animations.Animations
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.FragmentVersionsListBinding
import com.movtery.zalithlauncher.databinding.ItemFavoriteFolderBinding
import com.movtery.zalithlauncher.databinding.ViewSingleActionPopupBinding
import com.movtery.zalithlauncher.event.single.RefreshVersionsEvent
import com.movtery.zalithlauncher.event.single.RefreshVersionsEvent.MODE.END
import com.movtery.zalithlauncher.event.single.RefreshVersionsEvent.MODE.START
import com.movtery.zalithlauncher.event.sticky.FileSelectorEvent
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathManager
import com.movtery.zalithlauncher.feature.version.Version
import com.movtery.zalithlauncher.feature.version.VersionsManager
import com.movtery.zalithlauncher.feature.version.favorites.FavoritesVersionUtils
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.EditTextDialog
import com.movtery.zalithlauncher.ui.dialog.FavoritesVersionDialog
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import com.movtery.zalithlauncher.ui.layout.AnimRelativeLayout
import com.movtery.zalithlauncher.ui.subassembly.customprofilepath.ProfileItem
import com.movtery.zalithlauncher.ui.subassembly.customprofilepath.ProfilePathAdapter
import com.movtery.zalithlauncher.ui.subassembly.version.VersionAdapter
import com.movtery.zalithlauncher.utils.StoragePermissionsUtils
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.path.PathManager
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import java.util.UUID

class VersionsListFragment : FragmentWithAnim(R.layout.fragment_versions_list) {

    companion object {
        const val TAG: String = "VersionsListFragment"

        // Temporary SAF storage keys.
        // This keeps the selected SD card folder separate from ProfilePathManager,
        // which is still fully file-path based.
        private const val SD_CARD_PREFS = "sd_card_storage_prefs"
        private const val KEY_SD_TREE_URI = "sd_tree_uri"
        private const val KEY_SD_TREE_NAME = "sd_tree_name"
        private const val MODE_PRIVATE = 0
        private const val DEFAULT_PROFILE_ID = "default"
    }

    private lateinit var binding: FragmentVersionsListBinding
    private var versionsAdapter: VersionAdapter? = null
    private var profilePathAdapter: ProfilePathAdapter? = null
    private val favoriteFolderViews = mutableListOf<View>()

    private lateinit var openTreeLauncher: ActivityResultLauncher<Uri?>

    private val favoritesActionPopupWindow: PopupWindow = PopupWindow().apply {
        isFocusable = true
        isOutsideTouchable = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        openTreeLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            handlePickedTreeUri(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        handlePendingFileSelectorEvent()
        binding = FragmentVersionsListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupButtons()
        setupAdapters()
        refresh()
    }

    private fun handlePendingFileSelectorEvent() {
        val selectorEvent = EventBus.getDefault().getStickyEvent(FileSelectorEvent::class.java) ?: return

        selectorEvent.path?.let { path ->
            if (path.isNotEmpty() && !ProfilePathManager.containsPath(path)) {
                EditTextDialog.Builder(requireContext())
                    .setTitle(R.string.profiles_path_create_new_title)
                    .setAsRequired()
                    .setConfirmListener { editBox, _ ->
                        val title = editBox.text.toString().trim()
                        if (title.isEmpty()) {
                            editBox.error = getString(R.string.generic_error_field_empty)
                            return@setConfirmListener false
                        }

                        ProfilePathManager.addPath(
                            ProfileItem(
                                UUID.randomUUID().toString(),
                                title,
                                path
                            )
                        )
                        refresh()
                        true
                    }
                    .showDialog()
            }
        }

        EventBus.getDefault().removeStickyEvent(selectorEvent)
    }

    private fun setupButtons() {
        binding.installNew.setOnClickListener {
            ZHTools.swapFragmentWithAnim(
                this@VersionsListFragment,
                VersionSelectorFragment::class.java,
                VersionSelectorFragment.TAG,
                null
            )
        }

        binding.favoritesFolderTab.observeIndexChange { _, toIndex, reselect, fromUser ->
            if (fromUser && !reselect) {
                refreshFavoritesSelection(toIndex)
            }
        }

        binding.favoritesActions.setOnClickListener {
            showFavoritesActionPopupWindow(it)
        }

        binding.refreshButton.setOnClickListener {
            binding.refreshButton.isEnabled = false
            refresh(refreshVersions = true, refreshVersionInfo = true)
        }

        binding.createPathButton.setOnClickListener {
            StoragePermissionsUtils.ensurePermissions(
                activity = requireActivity(),
                title = R.string.profiles_path_create_new,
                permissionResult = object : StoragePermissionsUtils.PermissionResult {
                    override fun onGranted() {
                        val bundle = Bundle().apply {
                            putBoolean(FilesFragment.BUNDLE_SELECT_FOLDER_MODE, true)
                            putBoolean(FilesFragment.BUNDLE_SHOW_FILE, false)
                            putBoolean(FilesFragment.BUNDLE_REMOVE_LOCK_PATH, false)
                        }

                        ZHTools.swapFragmentWithAnim(
                            this@VersionsListFragment,
                            FilesFragment::class.java,
                            FilesFragment.TAG,
                            bundle
                        )
                    }

                    override fun onCancelled() = Unit
                }
            )
        }

        // Requires a new button in fragment_versions_list.xml:
        // @+id/pick_sd_card_button
        binding.pickSdCardButton.visibility = View.GONE
        binding.pickSdCardButton.setOnClickListener {
            openTreeLauncher.launch(null)
        }

        binding.returnButton.setOnClickListener {
            ZHTools.onBackPressed(requireActivity())
        }
    }

    private fun setupAdapters() {
        versionsAdapter = VersionAdapter(
            this@VersionsListFragment,
            object : VersionAdapter.OnVersionItemClickListener {
                override fun showFavoritesDialog(versionName: String) {
                    if (FavoritesVersionUtils.getFavoritesStructure().isNotEmpty()) {
                        FavoritesVersionDialog(requireActivity(), versionName) {
                            refreshFavoritesSelection(binding.favoritesFolderTab.currentItemIndex)
                        }.show()
                    } else {
                        Toast.makeText(
                            requireActivity(),
                            getString(R.string.version_manager_favorites_dialog_no_folders),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun isVersionFavorited(versionName: String): Boolean {
                    if (binding.favoritesFolderTab.currentItemIndex != 0) {
                        return true
                    }

                    return FavoritesVersionUtils.getFavoritesStructure().values.any { versions ->
                        versions.contains(versionName)
                    }
                }
            }
        )

        binding.versions.apply {
            layoutAnimation = LayoutAnimationController(
                AnimationUtils.loadAnimation(view?.context ?: requireContext(), R.anim.fade_downwards)
            )
            layoutManager = LinearLayoutManager(requireContext())
            adapter = versionsAdapter
        }

        profilePathAdapter = ProfilePathAdapter(this@VersionsListFragment, binding.profilesPath)
        binding.profilesPath.apply {
            layoutAnimation = LayoutAnimationController(
                AnimationUtils.loadAnimation(view?.context ?: requireContext(), R.anim.fade_downwards)
            )
            layoutManager = LinearLayoutManager(requireContext())
            adapter = profilePathAdapter
        }
    }

    private fun refreshFavoritesSelection(index: Int) {
        when (index) {
            0 -> refreshVersions(true)
            else -> {
                val folderNames = FavoritesVersionUtils.getFavoritesStructure().keys.toList()
                val folderName = folderNames.getOrNull(index - 1)
                refreshVersions(false, folderName)
            }
        }
    }

    /**
     * Important:
     * We do not add the tree Uri into ProfilePathManager.
     * ProfilePathManager and the install pipeline are still file-path based,
     * and saving a content:// tree Uri there would break installs.
     *
     * For now, this only stores the selected SD card folder separately.
     */
    private fun handlePickedTreeUri(uri: Uri?) {
        if (uri == null) return

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        try {
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some providers may already have granted access,
            // or may reject persisting the permission again.
        }

        EditTextDialog.Builder(requireContext())
            .setTitle(R.string.profiles_path_create_new_title)
            .setAsRequired()
            .setEditText(getSavedSdTreeName() ?: getString(R.string.profiles_path_title))
            .setConfirmListener { editBox, _ ->
                val name = editBox.text.toString().trim()
                if (name.isEmpty()) {
                    editBox.error = getString(R.string.generic_error_field_empty)
                    return@setConfirmListener false
                }

                saveSdTree(uri, name)

                Toast.makeText(
                    requireContext(),
                    "SD card folder saved separately. It is not yet used as a direct install path.",
                    Toast.LENGTH_LONG
                ).show()

                true
            }
            .showDialog()
    }

    private fun getPrefs(): SharedPreferences {
        return requireContext().getSharedPreferences(SD_CARD_PREFS, Context.MODE_PRIVATE)
    }

    private fun saveSdTree(uri: Uri, name: String) {
        getPrefs().edit()
            .putString(KEY_SD_TREE_URI, uri.toString())
            .putString(KEY_SD_TREE_NAME, name)
            .apply()
    }

    private fun getSavedSdTreeName(): String? {
        return getPrefs().getString(KEY_SD_TREE_NAME, null)
    }

    private fun refresh(
        refreshVersions: Boolean = false,
        refreshVersionInfo: Boolean = false
    ) {
        ProfilePathManager.refreshPath()

        if (refreshVersions) {
            VersionsManager.refresh("VersionsListFragment:refresh", refreshVersionInfo)
        } else {
            refreshFavoritesFolderAndVersions()
        }

        val paths = buildList {
            add(
                ProfileItem(
                    DEFAULT_PROFILE_ID,
                    getString(R.string.profiles_path_default),
                    PathManager.DIR_GAME_HOME
                )
            )
            addAll(ProfilePathManager.getAllPath())
        }

        profilePathAdapter?.updateData(paths as MutableList<ProfileItem>)
    }

    private fun refreshVersions(all: Boolean = true, favoritesFolder: String? = null) {
        val adapter = versionsAdapter ?: return
        val versions = VersionsManager.getVersions()

        val filteredVersions: List<Version> = if (all) {
            versions
        } else {
            val folderName = favoritesFolder.orEmpty()
            val folderVersions = FavoritesVersionUtils.getValidVersions(folderName)
            versions.filter { version ->
                folderVersions.contains(version.getVersionName())
            }
        }

        val currentIndex = adapter.refreshVersions(filteredVersions)
        binding.versions.scrollToPosition(currentIndex)
        binding.versions.scheduleLayoutAnimation()
    }

    private fun refreshFavoritesFolderAndVersions() {
        binding.favoritesFolderTab.setCurrentItem(0)
        refreshVersions()

        favoriteFolderViews.forEach { view ->
            binding.favoritesFolderTab.removeView(view)
        }
        favoriteFolderViews.clear()

        FavoritesVersionUtils.getFavoritesStructure().forEach { (folderName, _) ->
            val folderView = createFavoriteFolderView(folderName)
            favoriteFolderViews.add(folderView)
            binding.favoritesFolderTab.addView(folderView)
        }
    }

    private fun createFavoriteFolderView(folderName: String): AnimRelativeLayout {
        return ItemFavoriteFolderBinding.inflate(layoutInflater).apply {
            name.text = folderName
            root.layoutParams = DslTabLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            root.setOnLongClickListener {
                showFavoritesDeletePopupWindow(root, folderName)
                true
            }
        }.root
    }

    private fun refreshActionPopupWindow(anchorView: View, popupBinding: ViewBinding) {
        favoritesActionPopupWindow.apply {
            popupBinding.root.measure(0, 0)
            contentView = popupBinding.root
            width = popupBinding.root.measuredWidth
            height = popupBinding.root.measuredHeight
            showAsDropDown(anchorView)
        }
    }

    private fun showFavoritesActionPopupWindow(anchorView: View) {
        refreshActionPopupWindow(
            anchorView,
            ViewSingleActionPopupBinding.inflate(LayoutInflater.from(requireActivity())).apply {
                icon.setImageDrawable(
                    ContextCompat.getDrawable(requireActivity(), R.drawable.ic_add)
                )
                text.setText(R.string.version_manager_favorites_add_category)
                text.setOnClickListener {
                    EditTextDialog.Builder(requireActivity())
                        .setTitle(R.string.version_manager_favorites_write_folder_name)
                        .setAsRequired()
                        .setConfirmListener { editText, _ ->
                            val folderName = editText.text.toString().trim()
                            if (folderName.isEmpty()) {
                                editText.error = getString(R.string.generic_error_field_empty)
                                return@setConfirmListener false
                            }

                            FavoritesVersionUtils.addFolder(folderName)
                            refreshFavoritesFolderAndVersions()
                            true
                        }
                        .showDialog()

                    favoritesActionPopupWindow.dismiss()
                }
            }
        )
    }

    private fun showFavoritesDeletePopupWindow(anchorView: View, folderName: String) {
        refreshActionPopupWindow(
            anchorView,
            ViewSingleActionPopupBinding.inflate(LayoutInflater.from(requireActivity())).apply {
                icon.setImageDrawable(
                    ContextCompat.getDrawable(requireActivity(), R.drawable.ic_menu_delete_forever)
                )
                text.setText(R.string.version_manager_favorites_remove_folder_title)
                text.setOnClickListener {
                    TipDialog.Builder(requireActivity())
                        .setTitle(R.string.version_manager_favorites_remove_folder_title)
                        .setMessage(R.string.version_manager_favorites_remove_folder_message)
                        .setWarning()
                        .setConfirmClickListener {
                            FavoritesVersionUtils.removeFolder(folderName)
                            refreshFavoritesFolderAndVersions()
                        }
                        .showDialog()

                    favoritesActionPopupWindow.dismiss()
                }
            }
        )
    }

    @Subscribe
    fun event(event: RefreshVersionsEvent) {
        TaskExecutors.runInUIThread {
            when (event.mode) {
                START -> {
                    binding.refreshButton.isEnabled = false
                    binding.favoritesFolderTab.isEnabled = false
                    binding.versions.visibility = View.GONE
                    binding.refreshVersions.visibility = View.VISIBLE
                }

                END -> {
                    refreshFavoritesFolderAndVersions()
                    binding.favoritesFolderTab.isEnabled = true
                    binding.refreshButton.isEnabled = true
                    binding.versions.visibility = View.VISIBLE
                    binding.refreshVersions.visibility = View.GONE
                }
            }

            closeAllPopupWindow()
        }
    }

    private fun closeAllPopupWindow() {
        versionsAdapter?.closePopupWindow()
        profilePathAdapter?.closePopupWindow()
        favoritesActionPopupWindow.dismiss()
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
    }

    override fun onPause() {
        super.onPause()
        closeAllPopupWindow()
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(versionsListLayout, Animations.BounceInUp))
                .apply(AnimPlayer.Entry(versionTopBar, Animations.BounceInDown))
                .apply(AnimPlayer.Entry(operateLayout, Animations.BounceInLeft))
        }
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(versionsListLayout, Animations.FadeOutDown))
                .apply(AnimPlayer.Entry(versionTopBar, Animations.FadeOutUp))
                .apply(AnimPlayer.Entry(operateLayout, Animations.FadeOutRight))
        }
    }
}