package com.movtery.zalithlauncher.ui.fragment

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.getkeepsafe.taptargetview.TapTargetSequence
import com.movtery.anim.AnimPlayer
import com.movtery.anim.animations.Animations
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.FragmentFilesBinding
import com.movtery.zalithlauncher.event.sticky.FileSelectorEvent
import com.movtery.zalithlauncher.task.Task
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.EditTextDialog
import com.movtery.zalithlauncher.ui.dialog.FilesDialog
import com.movtery.zalithlauncher.ui.dialog.FilesDialog.FilesButton
import com.movtery.zalithlauncher.ui.subassembly.filelist.FileItemBean
import com.movtery.zalithlauncher.ui.subassembly.filelist.FileSelectedListener
import com.movtery.zalithlauncher.ui.subassembly.view.SearchViewWrapper
import com.movtery.zalithlauncher.utils.NewbieGuideUtils
import com.movtery.zalithlauncher.utils.StoragePermissionsUtils
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.anim.AnimUtils.Companion.setVisibilityAnim
import com.movtery.zalithlauncher.utils.file.FileTools
import com.movtery.zalithlauncher.utils.file.PasteFile
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension
import org.greenrobot.eventbus.EventBus
import java.io.File
import com.movtery.zalithlauncher.utils.path.PathManager

class FilesFragment : FragmentWithAnim(R.layout.fragment_files) {
    private var primaryStorageRoot: File? = null
    private var removableStorageRoot: File? = null

    companion object {
        const val TAG: String = "FilesFragment"
        const val BUNDLE_LOCK_PATH: String = "bundle_lock_path"
        const val BUNDLE_LIST_PATH: String = "bundle_list_path"
        const val BUNDLE_SHOW_FILE: String = "show_file"
        const val BUNDLE_SHOW_FOLDER: String = "show_folder"
        const val BUNDLE_QUICK_ACCESS_PATHS: String = "quick_access_paths"
        const val BUNDLE_MULTI_SELECT_MODE: String = "multi_select_mode"
        const val BUNDLE_SELECT_FOLDER_MODE: String = "select_folder_mode"
        const val BUNDLE_REMOVE_LOCK_PATH: String = "remove_lock_path"
        const val BUNDLE_TITLE_REMOVE_LOCK_PATH: String = "title_remove_lock_path"
    }

    private lateinit var binding: FragmentFilesBinding
    private lateinit var searchViewWrapper: SearchViewWrapper
    private var openDocumentLauncher: ActivityResultLauncher<Any?>? = null

    private var showFiles = false
    private var showFolders = false
    private var quickAccessPaths = false
    private var multiSelectMode = false
    private var selectFolderMode = false
    private var removeLockPath = false
    private var titleRemoveLockPath = false
    private var lockPath: String? = null
    private var listPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        openDocumentLauncher = registerForActivityResult(
            OpenDocumentWithExtension(null, true)
        ) { uris: List<Uri>? ->
            uris?.let(::importFiles)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentFilesBinding.inflate(inflater, container, false)
        searchViewWrapper = SearchViewWrapper(this)
        return binding.root
    }

    /*@SuppressLint("UseCompatLoadingForDrawables")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        parseBundle()
        initViews()
        setupFileRecyclerView()
        setupButtons()
        openInitialPath()
        startNewbieGuide()
    }*/
    @SuppressLint("UseCompatLoadingForDrawables")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        parseBundle()
        resolveStorageRoots()
        initViews()
        setupFileRecyclerView()
        setupButtons()
        openInitialPath()
        startNewbieGuide()
    }
    private fun resolveStorageRoots() {
        primaryStorageRoot = PathManager.getPrimaryStorageRoot(requireContext())
        removableStorageRoot = PathManager.getRemovableStorageRoot(requireContext())
    }
    private fun importFiles(uriList: List<Uri>) {
        val dialog = ZHTools.showTaskRunningDialog(requireContext())

        Task.runTask {
            uriList.forEach { uri ->
                FileTools.copyFileInBackground(
                    requireContext(),
                    uri,
                    binding.fileRecyclerView.fullPath.absolutePath
                )
            }
        }.ended(TaskExecutors.getAndroidUI()) {
            Toast.makeText(requireContext(), getString(R.string.file_added), Toast.LENGTH_SHORT).show()
            binding.fileRecyclerView.refreshPath()
        }.onThrowable { e ->
            Tools.showErrorRemote(e)
        }.finallyTask(TaskExecutors.getAndroidUI()) {
            dialog.dismiss()
        }.execute()
    }

    /*private fun setupFileRecyclerView() {
        val storageDirectory = Environment.getExternalStorageDirectory()

        binding.fileRecyclerView.apply {
            setShowFiles(showFiles)
            setShowFolders(showFolders)

            setTitleListener { title ->
                binding.currentPath.text = formatDisplayedPath(title.orEmpty(), titleRemoveLockPath)
            }

            setFileSelectedListener(object : FileSelectedListener() {
                override fun onFileSelected(file: File?, path: String?) {
                    file?.let(::showDialog)
                }

                override fun onItemLongClick(file: File?, path: String?) {
                    file?.takeIf { it.isDirectory }?.let(::showDialog)
                }
            })

            setOnMultiSelectListener { itemBeans ->
                if (itemBeans.isNotEmpty()) {
                    handleMultiSelect(itemBeans)
                }
            }

            setRefreshListener {
                setVisibilityAnim(binding.nothingText, isNoFile)

                // If the current directory changes to external storage,
                // check storage permission.
                if (fullPath.absolutePath == storageDirectory.absolutePath) {
                    StoragePermissionsUtils.ensurePermissions(
                        activity = requireActivity(),
                        title = R.string.file_external_storage,
                        permissionResult = null
                    )
                }
            }
        }
    }*/
    private fun setupFileRecyclerView() {
        binding.fileRecyclerView.apply {
            setShowFiles(showFiles)
            setShowFolders(showFolders)

            setTitleListener { title ->
                binding.currentPath.text = formatDisplayedPath(title.orEmpty(), titleRemoveLockPath)
            }

            setFileSelectedListener(object : FileSelectedListener() {
                override fun onFileSelected(file: File?, path: String?) {
                    file?.let(::showDialog)
                }

                override fun onItemLongClick(file: File?, path: String?) {
                    file?.takeIf { it.isDirectory }?.let(::showDialog)
                }
            })

            setOnMultiSelectListener { itemBeans ->
                if (itemBeans.isNotEmpty()) {
                    handleMultiSelect(itemBeans)
                }
            }

            setRefreshListener {
                setVisibilityAnim(binding.nothingText, isNoFile)

                val removableRoot = removableStorageRoot
                if (removableRoot != null && PathManager.isPathInsideRoot(fullPath, removableRoot)) {
                    StoragePermissionsUtils.ensurePermissions(
                        activity = requireActivity(),
                        title = R.string.file_external_storage,
                        permissionResult = null
                    )
                }
            }
        }
    }

    private fun setupButtons() {


        binding.currentPath.setOnClickListener {
            EditTextDialog.Builder(requireContext())
                .setTitle(R.string.file_jump_to_path)
                .setEditText(binding.fileRecyclerView.fullPath.absolutePath)
                .setAsRequired()
                .setConfirmListener { editBox, _ ->
                    /*val path = editBox.text.toString()
                    val targetFile = File(path)
                    val currentLockPath = lockPath.orEmpty()

                    // The path must be inside the locked root, must exist,
                    // and must be a directory.
                    if (!path.contains(currentLockPath) || !targetFile.exists() || !targetFile.isDirectory) {
                        editBox.error = getString(R.string.file_does_not_exist)
                        return@setConfirmListener false
                    }*/
                    val path = editBox.text.toString().trim()
                    val targetFile = File(path)
                    val rootFile = File(lockPath.orEmpty())

                    if (!targetFile.exists()
                        || !targetFile.isDirectory
                        || !PathManager.isPathInsideRoot(targetFile, rootFile)
                    ) {
                        editBox.error = getString(R.string.file_does_not_exist)
                        return@setConfirmListener false
                    }

                    binding.fileRecyclerView.listFileAt(targetFile)
                    true
                }
                .showDialog()
        }

        /*binding.externalStorage.setOnClickListener {
            closeMultiSelect()
            binding.fileRecyclerView.listFileAt(Environment.getExternalStorageDirectory())
        }

        binding.softwarePrivate.setOnClickListener {
            closeMultiSelect()
            binding.fileRecyclerView.listFileAt(requireContext().getExternalFilesDir(null))
        }*/
        binding.externalStorage.setOnClickListener {
            closeMultiSelect()
            binding.fileRecyclerView.listFileAt(
                primaryStorageRoot ?: PathManager.getPrimaryStorageRoot(requireContext())
            )
        }

        binding.softwarePrivate.setOnClickListener {
            closeMultiSelect()
            val target = removableStorageRoot ?: requireContext().getExternalFilesDir(null)
            if (target != null) {
                binding.fileRecyclerView.listFileAt(target)
            } else {
                Toast.makeText(requireContext(), R.string.file_does_not_exist, Toast.LENGTH_SHORT).show()
            }
        }

        val adapter = binding.fileRecyclerView.adapter

        binding.multiSelectFiles.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            binding.selectAll.apply {
                this.isChecked = false
                visibility = if (isChecked) View.VISIBLE else View.GONE
            }

            adapter.setMultiSelectMode(isChecked)

            if (searchViewWrapper.isVisible()) {
                searchViewWrapper.setVisibility(!isChecked)
            }
        }

        binding.selectAll.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            adapter.selectAllFiles(isChecked)
        }

        binding.operateView.returnButton.setOnClickListener {
            if (!selectFolderMode) {
                closeMultiSelect()
                Tools.removeCurrentFragment(requireActivity())
            } else {
                /*EventBus.getDefault().postSticky(
                    FileSelectorEvent(
                        formatDisplayedPath(
                            binding.fileRecyclerView.fullPath.absolutePath,
                            removeLockPath
                        )
                    )
                )*/
                EventBus.getDefault().postSticky(
                    FileSelectorEvent(binding.fileRecyclerView.fullPath.absolutePath)
                )
                Tools.removeCurrentFragment(requireActivity())
            }
        }

        binding.operateView.addFileButton.setOnClickListener {
            closeMultiSelect()
            openDocumentLauncher?.launch(null)
        }

        binding.operateView.createFolderButton.setOnClickListener {
            closeMultiSelect()
            showCreateFolderDialog()
        }

        binding.operateView.pasteButton.setOnClickListener {
            PasteFile.getInstance().pasteFiles(
                requireActivity(),
                binding.fileRecyclerView.fullPath,
                null,
                Task.runTask(TaskExecutors.getAndroidUI()) {
                    closeMultiSelect()
                    binding.operateView.pasteButton.visibility = View.GONE
                    binding.fileRecyclerView.refreshPath()
                }
            )
        }

        binding.operateView.searchButton.setOnClickListener {
            closeMultiSelect()
            searchViewWrapper.setVisibility()
        }

        binding.operateView.refreshButton.setOnClickListener {
            closeMultiSelect()
            binding.fileRecyclerView.refreshPath()
        }
    }

    private fun handleMultiSelect(itemBeans: List<FileItemBean>) {
        Task.runTask {
            itemBeans.mapNotNull { it.file }
        }.ended(TaskExecutors.getAndroidUI()) { selectedFiles ->
            val filesButton = FilesButton().apply {
                setButtonVisibility(true, true, false, false, true, false)
                setDialogText(
                    getString(R.string.file_multi_select_mode_title),
                    getString(R.string.file_multi_select_mode_message, itemBeans.size),
                    null
                )
            }

            val filesDialog = FilesDialog(
                requireContext(),
                filesButton,
                Task.runTask(TaskExecutors.getAndroidUI()) {
                    closeMultiSelect()
                    refreshPath()
                },
                binding.fileRecyclerView.fullPath,
                selectedFiles ?: emptyList()
            )

            filesDialog.setCopyButtonClick {
                binding.operateView.pasteButton.visibility = View.VISIBLE
            }
            filesDialog.show()
        }.execute()
    }

    private fun showCreateFolderDialog() {
        EditTextDialog.Builder(requireContext())
            .setTitle(R.string.file_folder_dialog_insert_name)
            .setAsRequired()
            .setConfirmListener { editBox, _ ->
                val name = editBox.text.toString()

                if (name.contains("/")) {
                    val folderNames = name.split("/")
                    val hasInvalidName = folderNames.any { folderName ->
                        FileTools.isFilenameInvalid(
                            folderName,
                            { illegalCharacters ->
                                editBox.error = getString(
                                    R.string.generic_input_invalid_character,
                                    illegalCharacters
                                )
                            },
                            { invalidLength ->
                                editBox.error = getString(
                                    R.string.file_invalid_length,
                                    invalidLength,
                                    255
                                )
                            }
                        )
                    }
                    if (hasInvalidName) return@setConfirmListener false
                } else if (FileTools.isFilenameInvalid(editBox)) {
                    return@setConfirmListener false
                }

                val folder = File(binding.fileRecyclerView.fullPath, name)
                if (folder.exists()) {
                    editBox.error = getString(R.string.file_rename_exitis)
                    return@setConfirmListener false
                }

                val success = folder.mkdirs()
                if (success) {
                    binding.fileRecyclerView.listFileAt(folder)
                } else {
                    binding.fileRecyclerView.refreshPath()
                }

                true
            }
            .showDialog()
    }

    private fun openInitialPath() {
        val currentLockPath = File(lockPath.orEmpty())

        binding.fileRecyclerView.apply {
            listPath?.let {
                lockAndListAt(currentLockPath, File(it))
                return
            }
            lockAndListAt(currentLockPath, currentLockPath)
        }
    }

    private fun startNewbieGuide() {
        if (NewbieGuideUtils.showOnlyOne("${TAG}${if (selectFolderMode) "_select" else ""}")) {
            return
        }

        binding.operateView.apply {
            val activity = requireActivity()
            val refreshTarget = NewbieGuideUtils.getSimpleTarget(
                activity,
                refreshButton,
                getString(R.string.generic_refresh),
                getString(R.string.newbie_guide_general_refresh)
            )
            val searchTarget = NewbieGuideUtils.getSimpleTarget(
                activity,
                searchButton,
                getString(R.string.generic_search),
                getString(R.string.newbie_guide_file_search)
            )
            val createFolderTarget = NewbieGuideUtils.getSimpleTarget(
                activity,
                createFolderButton,
                getString(R.string.file_create_folder),
                getString(R.string.newbie_guide_file_create_folder)
            )

            if (selectFolderMode) {
                TapTargetSequence(activity)
                    .targets(
                        refreshTarget,
                        searchTarget,
                        createFolderTarget,
                        NewbieGuideUtils.getSimpleTarget(
                            activity,
                            returnButton,
                            getString(R.string.file_select_folder),
                            getString(R.string.newbie_guide_file_select)
                        )
                    )
                    .start()
            } else {
                TapTargetSequence(activity)
                    .targets(
                        refreshTarget,
                        searchTarget,
                        NewbieGuideUtils.getSimpleTarget(
                            activity,
                            addFileButton,
                            getString(R.string.file_add_file),
                            getString(R.string.newbie_guide_file_import)
                        ),
                        createFolderTarget,
                        NewbieGuideUtils.getSimpleTarget(
                            activity,
                            returnButton,
                            getString(R.string.generic_close),
                            getString(R.string.newbie_guide_general_close)
                        )
                    )
                    .start()
            }
        }
    }

    private fun closeMultiSelect() {
        // Disable multi-select mode when other controls are used.
        binding.multiSelectFiles.isChecked = false
        binding.selectAll.visibility = View.GONE
    }

    private fun showDialog(file: File) {
        val filesButton = FilesButton().apply {
            setButtonVisibility(true, true, true, true, true, false)
            setMessageText(
                if (file.isDirectory) {
                    getString(R.string.file_folder_message)
                } else {
                    getString(R.string.file_message)
                }
            )
        }

        val filesDialog = FilesDialog(
            requireContext(),
            filesButton,
            Task.runTask(TaskExecutors.getAndroidUI()) {
                binding.fileRecyclerView.refreshPath()
            },
            binding.fileRecyclerView.fullPath,
            file
        )

        filesDialog.setCopyButtonClick {
            binding.operateView.pasteButton.visibility = View.VISIBLE
        }
        filesDialog.show()
    }

    private fun formatDisplayedPath(path: String, removeLockedRoot: Boolean): String {
        if (!removeLockedRoot) {
            return path
        }

        return path.replace(lockPath.orEmpty(), ".")
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun initViews() {
        binding.apply {
            searchViewWrapper.setSearchListener(object : SearchViewWrapper.SearchListener {
                override fun onSearch(string: String?, caseSensitive: Boolean): Int {
                    return fileRecyclerView.searchFiles(string, caseSensitive)
                }
            })

            searchViewWrapper.setShowSearchResultsListener(object :
                SearchViewWrapper.ShowSearchResultsListener {
                override fun onSearch(show: Boolean) {
                    fileRecyclerView.setShowSearchResultsOnly(show)
                }
            })

            if (!quickAccessPaths) {
                externalStorage.visibility = View.GONE
                softwarePrivate.visibility = View.GONE
            }

            if (selectFolderMode || !multiSelectMode) {
                multiSelectFiles.visibility = View.GONE
                selectAll.visibility = View.GONE
            }

            if (selectFolderMode) {
                operateView.addFileButton.visibility = View.GONE
                operateView.returnButton.apply {
                    contentDescription = getString(R.string.file_select_folder)
                    setImageDrawable(resources.getDrawable(R.drawable.ic_check, requireActivity().theme))
                }
            }
            if (removableStorageRoot != null) {
                softwarePrivate.contentDescription = "SD card"
            }
        }

        binding.operateView.apply {
            pasteButton.visibility =
                if (PasteFile.getInstance().pasteType != null) View.VISIBLE else View.GONE

            ZHTools.setTooltipText(
                returnButton,
                addFileButton,
                createFolderButton,
                pasteButton,
                searchButton,
                refreshButton
            )
        }
    }

    private fun refreshPath() {
        binding.fileRecyclerView.refreshPath()
    }

    private fun parseBundle() {
        val bundle = arguments ?: return

        /*lockPath = bundle.getString(
            BUNDLE_LOCK_PATH,
            Environment.getExternalStorageDirectory().absolutePath
        )*/
        lockPath = bundle.getString(
            BUNDLE_LOCK_PATH,
            PathManager.getPrimaryStorageRoot(requireContext()).absolutePath
        )
        listPath = bundle.getString(BUNDLE_LIST_PATH, null)
        showFiles = bundle.getBoolean(BUNDLE_SHOW_FILE, true)
        showFolders = bundle.getBoolean(BUNDLE_SHOW_FOLDER, true)
        quickAccessPaths = bundle.getBoolean(BUNDLE_QUICK_ACCESS_PATHS, true)
        multiSelectMode = bundle.getBoolean(BUNDLE_MULTI_SELECT_MODE, true)
        selectFolderMode = bundle.getBoolean(BUNDLE_SELECT_FOLDER_MODE, false)
        removeLockPath = bundle.getBoolean(BUNDLE_REMOVE_LOCK_PATH, true)
        titleRemoveLockPath = bundle.getBoolean(BUNDLE_TITLE_REMOVE_LOCK_PATH, true)
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(filesLayout, Animations.BounceInDown))
                .apply(AnimPlayer.Entry(operateLayout, Animations.BounceInLeft))
        }
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(filesLayout, Animations.FadeOutUp))
                .apply(AnimPlayer.Entry(operateLayout, Animations.FadeOutRight))
        }
    }
}