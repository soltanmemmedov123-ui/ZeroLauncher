package com.movtery.zalithlauncher.feature.accounts

import android.content.Context
import com.kdt.mcgui.ProgressLayout
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.task.Task
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.authenticator.listener.DoneListener
import net.kdt.pojavlaunch.authenticator.listener.ErrorListener
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin
import net.kdt.pojavlaunch.value.MinecraftAccount
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class AccountUtils {
    companion object {
        @JvmStatic
        fun microsoftLogin(
            context: Context,
            account: MinecraftAccount,
            doneListener: DoneListener,
            errorListener: ErrorListener
        ) {
            MicrosoftBackgroundLogin(true, account.msaRefreshToken)
                .performLogin(context, account, doneListener, errorListener)
        }

        @JvmStatic
        fun otherLogin(
            context: Context,
            account: MinecraftAccount,
            doneListener: DoneListener,
            errorListener: ErrorListener
        ) {
            fun clearProgress() {
                ProgressLayout.clearProgress(ProgressLayout.LOGIN_ACCOUNT)
            }

            Task.runTask {
                OtherLoginHelper(
                    account.otherBaseUrl,
                    account.accountType,
                    account.otherAccount,
                    account.otherPassword,
                    object : OtherLoginHelper.OnLoginListener {
                        override fun onLoading() {
                            ProgressLayout.setProgress(
                                ProgressLayout.LOGIN_ACCOUNT,
                                0,
                                R.string.account_login_start
                            )
                        }

                        override fun unLoading() {
                        }

                        override fun onSuccess(account: MinecraftAccount) {
                            account.save()
                            clearProgress()
                            doneListener.onLoginDone(account)
                        }

                        override fun onFailed(error: String) {
                            clearProgress()
                            errorListener.onLoginError(RuntimeException(error))
                        }
                    }
                ).justLogin(context, account)
            }.onThrowable { throwable ->
                errorListener.onLoginError(RuntimeException(throwable.message))
            }.execute()
        }

        @JvmStatic
        fun isOtherLoginAccount(account: MinecraftAccount): Boolean {
            return account.otherBaseUrl != null && account.otherBaseUrl != "0"
        }

        @JvmStatic
        fun isMicrosoftAccount(account: MinecraftAccount): Boolean {
            return account.accountType == AccountType.MICROSOFT.type
        }

        @JvmStatic
        fun isNoLoginRequired(account: MinecraftAccount?): Boolean {
            return account == null || account.accountType == AccountType.LOCAL.type
        }

        @JvmStatic
        fun getAccountTypeName(context: Context, account: MinecraftAccount): String {
            return when {
                isMicrosoftAccount(account) -> context.getString(R.string.account_microsoft_account)
                isOtherLoginAccount(account) -> account.accountType
                else -> context.getString(R.string.account_local_account)
            }
        }

        /**
         * Adapted from:
         * HMCL Core: AuthlibInjectorServer.java
         *
         * Original source:
         * https://github.com/HMCL-dev/HMCL/blob/main/HMCLCore/src/main/java/org/jackhuang/hmcl/auth/authlibinjector/AuthlibInjectorServer.java
         *
         * Copyright belongs to the original author and is used under the GPL v3 license.
         */
        @JvmStatic
        fun tryGetFullServerUrl(baseUrl: String): String {
            fun String.ensureTrailingSlash(): String {
                return if (endsWith("/")) this else "$this/"
            }

            var url = addHttpsIfMissing(baseUrl)

            runCatching {
                var connection = URL(url).openConnection() as HttpURLConnection

                connection.getHeaderField("x-authlib-injector-api-location")?.let { apiLocation ->
                    val absoluteApiLocation = URL(connection.url, apiLocation)
                    val normalizedCurrentUrl = url.ensureTrailingSlash()
                    val normalizedResolvedUrl = absoluteApiLocation.toString().ensureTrailingSlash()

                    if (normalizedCurrentUrl != normalizedResolvedUrl) {
                        connection.disconnect()
                        url = normalizedResolvedUrl
                        connection = absoluteApiLocation.openConnection() as HttpURLConnection
                    }
                }

                return url.ensureTrailingSlash()
            }.getOrElse { throwable ->
                Logging.e("getFullServerUrl", Tools.printToString(throwable))
            }

            return baseUrl
        }

        /**
         * Adapted from:
         * HMCL Core: AuthlibInjectorServer.java
         *
         * Original source:
         * https://github.com/HMCL-dev/HMCL/blob/main/HMCLCore/src/main/java/org/jackhuang/hmcl/auth/authlibinjector/AuthlibInjectorServer.java
         *
         * Copyright belongs to the original author and is used under the GPL v3 license.
         */
        private fun addHttpsIfMissing(baseUrl: String): String {
            return if (!baseUrl.startsWith("http://", true) &&
                !baseUrl.startsWith("https://", true)
            ) {
                "https://$baseUrl".lowercase(Locale.ROOT)
            } else {
                baseUrl.lowercase(Locale.ROOT)
            }
        }
    }
}