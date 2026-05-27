package com.movtery.zalithlauncher.ui.activity

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.movtery.zalithlauncher.InfoCenter
import com.movtery.zalithlauncher.InfoDistributor
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.ActivitySplashBinding
import com.movtery.zalithlauncher.feature.unpack.Components
import com.movtery.zalithlauncher.feature.unpack.Jre
import com.movtery.zalithlauncher.feature.unpack.UnpackComponentsTask
import com.movtery.zalithlauncher.feature.unpack.UnpackJreTask
import com.movtery.zalithlauncher.feature.unpack.UnpackSingleFilesTask
import com.movtery.zalithlauncher.task.Task
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import com.movtery.zalithlauncher.utils.StoragePermissionsUtils
import net.kdt.pojavlaunch.LauncherActivity
import net.kdt.pojavlaunch.MissingStorageActivity
import net.kdt.pojavlaunch.Tools

@SuppressLint("CustomSplashScreen")
class SplashActivity : BaseActivity() {
    private var isStarted = false
    private lateinit var binding: ActivitySplashBinding
    private lateinit var installableAdapter: InstallableAdapter
    private val items = mutableListOf<InstallableItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initItems()

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()

        if (!Tools.checkStorageRoot()) {
            startActivity(Intent(this, MissingStorageActivity::class.java))
            finish()
            return
        }

        handleInitialStoragePermissionCheck()
    }

    private fun setupViews() {
        binding.titleText.text = InfoDistributor.APP_NAME

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@SplashActivity)
            adapter = installableAdapter
        }

        binding.startButton.apply {
            isEnabled = false
            setOnClickListener {
                if (isStarted) return@setOnClickListener

                isStarted = true
                binding.splashText.setText(R.string.splash_screen_installing)
                installableAdapter.startAllTasks()
            }
        }
    }

    private fun handleInitialStoragePermissionCheck() {
        // On Android 9 and below, check legacy storage permissions instead of
        // the Android 11+ "manage all files" permission.
        //
        // The launcher does not strictly require the user to grant this permission,
        // but having it helps ensure files and folders can be created normally.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            !StoragePermissionsUtils.hasLegacyStoragePermissions(this)
        ) {
            TipDialog.Builder(this)
                .setTitle(R.string.generic_warning)
                .setMessage(InfoCenter.replaceName(this, R.string.permissions_write_external_storage))
                .setWarning()
                .setConfirmClickListener { requestLegacyStoragePermissions() }
                .setCancelClickListener { checkEnd() } // Respect the user's choice.
                .showDialog()
        } else {
            checkEnd()
        }
    }

    private fun requestLegacyStoragePermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            STORAGE_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            // Continue regardless of whether the user granted permission.
            // The launcher does not force this permission, but any problems
            // caused by denying it will affect later file operations.
            checkEnd()
        }
    }

    private fun initItems() {
        Components.entries.forEach { component ->
            val unpackTask = UnpackComponentsTask(this, component)
            if (!unpackTask.isCheckFailed()) {
                items.add(
                    InstallableItem(
                        component.displayName,
                        component.summary?.let(::getString),
                        unpackTask
                    )
                )
            }
        }

        Jre.entries.forEach { jre ->
            val unpackTask = UnpackJreTask(this, jre)
            if (!unpackTask.isCheckFailed()) {
                items.add(
                    InstallableItem(
                        jre.jreName,
                        getString(jre.summary),
                        unpackTask
                    )
                )
            }
        }

        items.sort()
        installableAdapter = InstallableAdapter(items) {
            toMain()
        }
    }

    private fun checkEnd() {
        installableAdapter.checkAllTask()

        Task.runTask {
            UnpackSingleFilesTask(this).run()
        }.execute()

        binding.startButton.isEnabled = true
    }

    private fun toMain() {
        startActivity(Intent(this, LauncherActivity::class.java))
        finish()
    }

    companion object {
        private const val STORAGE_PERMISSION_REQUEST_CODE = 100
    }
}