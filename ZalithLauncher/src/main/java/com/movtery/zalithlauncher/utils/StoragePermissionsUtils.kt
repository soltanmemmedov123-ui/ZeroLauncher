package com.movtery.zalithlauncher.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.movtery.zalithlauncher.InfoCenter
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.ui.dialog.TipDialog

class StoragePermissionsUtils {
    companion object {
        private const val REQUEST_CODE_STORAGE_PERMISSIONS = 1001

        @Volatile
        private var cachedHasStoragePermission: Boolean = false

        /**
         * Checks storage permission and updates the cached result.
         */
        @JvmStatic
        fun refreshPermissions(context: Context) {
            cachedHasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                hasManageAllFilesPermission()
            } else {
                hasLegacyStoragePermissions(context)
            }
        }

        /**
         * Returns the last cached storage permission state.
         *
         * Call [refreshPermissions] first if you need the latest value.
         */
        @JvmStatic
        fun hasCachedPermission(): Boolean = cachedHasStoragePermission

        /**
         * Checks storage permission and, if missing, shows a dialog to request it.
         */
        @JvmStatic
        fun ensurePermissions(
            activity: Activity,
            title: Int = R.string.generic_warning,
            message: String = getDefaultPermissionMessage(activity),
            permissionResult: PermissionResult?
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                handleAndroid11AndAbove(activity, title, message, permissionResult)
            } else {
                handleAndroid10AndBelow(activity, title, message, permissionResult)
            }
        }

        /**
         * Returns whether legacy external storage permissions are granted.
         *
         * Used for Android 10 and below.
         */
        @JvmStatic
        fun hasLegacyStoragePermissions(context: Context): Boolean {
            return ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
        }

        @JvmStatic
        @RequiresApi(Build.VERSION_CODES.R)
        fun hasManageAllFilesPermission(): Boolean {
            return Environment.isExternalStorageManager()
        }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun handleAndroid11AndAbove(
            activity: Activity,
            title: Int,
            message: String,
            permissionResult: PermissionResult?
        ) {
            if (!hasManageAllFilesPermission()) {
                showPermissionRequestDialog(activity, title, message, object : PermissionRequestAction {
                    override fun onRequest() {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                        activity.startActivityForResult(intent, REQUEST_CODE_STORAGE_PERMISSIONS)
                    }

                    override fun onCancel() {
                        permissionResult?.onCancelled()
                    }
                })
            } else {
                cachedHasStoragePermission = true
                permissionResult?.onGranted()
            }
        }

        private fun handleAndroid10AndBelow(
            activity: Activity,
            title: Int,
            message: String,
            permissionResult: PermissionResult?
        ) {
            if (!hasLegacyStoragePermissions(activity)) {
                showPermissionRequestDialog(activity, title, message, object : PermissionRequestAction {
                    override fun onRequest() {
                        ActivityCompat.requestPermissions(
                            activity,
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ),
                            REQUEST_CODE_STORAGE_PERMISSIONS
                        )
                    }

                    override fun onCancel() {
                        permissionResult?.onCancelled()
                    }
                })
            } else {
                cachedHasStoragePermission = true
                permissionResult?.onGranted()
            }
        }

        private fun showPermissionRequestDialog(
            context: Context,
            title: Int,
            message: String,
            requestAction: PermissionRequestAction
        ) {
            TipDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setConfirmClickListener { requestAction.onRequest() }
                .setCancelClickListener { requestAction.onCancel() }
                .setCancelable(false)
                .showDialog()
        }

        private fun getDefaultPermissionMessage(context: Context): String {
            return InfoCenter.replaceName(context, R.string.permissions_manage_external_storage)
        }
    }

    private interface PermissionRequestAction {
        fun onRequest()
        fun onCancel()
    }

    interface PermissionResult {
        fun onGranted()
        fun onCancelled()
    }
}