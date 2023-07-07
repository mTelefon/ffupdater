package de.marmaro.krt.ffupdater

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.MainThread
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy.KEEP
import androidx.work.NetworkType.CONNECTED
import androidx.work.NetworkType.UNMETERED
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkRequest.Companion.DEFAULT_BACKOFF_DELAY_MILLIS
import androidx.work.WorkerParameters
import de.marmaro.krt.ffupdater.app.App
import de.marmaro.krt.ffupdater.app.entity.AppAndUpdateStatus
import de.marmaro.krt.ffupdater.app.entity.AppUpdateStatus
import de.marmaro.krt.ffupdater.app.entity.InstallationStatus
import de.marmaro.krt.ffupdater.background.BackgroundException
import de.marmaro.krt.ffupdater.device.DeviceAbiExtractor
import de.marmaro.krt.ffupdater.device.DeviceSdkTester
import de.marmaro.krt.ffupdater.installer.ApkChecker
import de.marmaro.krt.ffupdater.installer.AppInstaller.Companion.createBackgroundAppInstaller
import de.marmaro.krt.ffupdater.installer.entity.Installer.NATIVE_INSTALLER
import de.marmaro.krt.ffupdater.installer.entity.Installer.SESSION_INSTALLER
import de.marmaro.krt.ffupdater.installer.exceptions.InstallationFailedException
import de.marmaro.krt.ffupdater.installer.exceptions.UserInteractionIsRequiredException
import de.marmaro.krt.ffupdater.network.NetworkUtil.isNetworkMetered
import de.marmaro.krt.ffupdater.network.exceptions.NetworkException
import de.marmaro.krt.ffupdater.network.file.CacheBehaviour.USE_CACHE
import de.marmaro.krt.ffupdater.network.file.FileDownloader
import de.marmaro.krt.ffupdater.notification.BackgroundNotificationBuilder
import de.marmaro.krt.ffupdater.notification.BackgroundNotificationRemover
import de.marmaro.krt.ffupdater.settings.BackgroundSettingsHelper
import de.marmaro.krt.ffupdater.settings.DataStoreHelper
import de.marmaro.krt.ffupdater.settings.InstallerSettingsHelper
import de.marmaro.krt.ffupdater.storage.StorageUtil
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit.MINUTES

/**
 * BackgroundJob will be regularly called by the AndroidX WorkManager to:
 * - check for app updates
 * - download them
 * - install them
 *
 * Depending on the device and the settings from the user not all steps will be executed.
 */
@Keep
class BackgroundJob(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context.applicationContext, workerParams) {
    private val context = applicationContext


    /**
     * Execute the logic for update checking, downloading and installation.
     *
     * If an common exception (like CancellationException, GithubRateLimitExceededException or
     * NetworkException) occurs, then retry it later.
     * If after 24 retries (five days) the background check still fails, then show a notification and
     * retry in the next regular execution interval.
     *
     * If an uncommon exception (anything else) occurs, then show a notification and retry in the next
     * regular execution interval.
     *
     * I can't return Result.error() because even if an unknown exception occurs, I want that BackgroundJob
     * is still regularly executed.
     * But Result.error() will remove BackgroundJob from the WorkManager job schedule.
     */
    @MainThread
    override suspend fun doWork(): Result = coroutineScope {
        try {
            Log.i(LOG_TAG, "Execute background job for update check.")
            internalDoWork()
        } catch (e: NetworkException) {
            handleDoWorkException(e, true)
        } catch (e: Exception) {
            handleDoWorkException(e, false)
        }
    }

    private fun handleDoWorkException(e: Exception, isNetworkException: Boolean): Result {
        return if (runAttemptCount < MAX_RETRIES) {
            Log.w(LOG_TAG, "Background job failed. Restart in ${calcBackoffTime(runAttemptCount)}", e)
            Result.retry()
        } else {
            val wrappedException = BackgroundException(e)
            Log.e(LOG_TAG, "Background job failed.", wrappedException)
            if (isNetworkException) {
                BackgroundNotificationBuilder.showNetworkErrorNotification(context, wrappedException)
            } else {
                BackgroundNotificationBuilder.showErrorNotification(context, wrappedException)
            }
            Result.success() // BackgroundJob should not be removed from WorkManager schedule
        }
    }

    @MainThread
    private suspend fun internalDoWork(): Result {
        BackgroundNotificationRemover.removeDownloadErrorNotification(context)
        BackgroundNotificationRemover.removeAppStatusNotifications(context)

        shouldUpdateCheckBeAborted()?.let { return it }

        val outdatedApps = checkForOutdatedApps()

        shouldDownloadsBeAborted()?.let {
            // execute if downloads should be aborted
            val apps = outdatedApps.map { (app, _) -> app }
            BackgroundNotificationBuilder.showUpdateAvailableNotification(context, apps)
            return it
        }

        val downloadedApps = outdatedApps.filter { (app, updateStatus) ->
            if (!StorageUtil.isEnoughStorageAvailable(context)) {
                Log.i(LOG_TAG, "Skip $app because not enough storage is available.")
                BackgroundNotificationBuilder.showUpdateAvailableNotification(context, app)
                return@filter false
            }
            downloadUpdateAndReturnAvailability(app, updateStatus)
        }

        shouldInstallationBeAborted()?.let {
            // execute if installation should be aborted
            val apps = downloadedApps.map { (app, _) -> app }
            BackgroundNotificationBuilder.showUpdateAvailableNotification(context, apps)
            return it
        }

        // update FFUpdater at last because an update will kill this update process
        val appsForInstallation = downloadedApps.sortedBy { (app, _) ->
            if (app != App.FFUPDATER) app.ordinal else Int.MAX_VALUE
        }

        appsForInstallation.forEach { (app, updateStatus) ->
            installApplication(app, updateStatus)
        }

        return Result.success()
    }

    private fun shouldUpdateCheckBeAborted(): Result? {
        if (!BackgroundSettingsHelper.isUpdateCheckEnabled) {
            Log.i(LOG_TAG, "Background should be disabled - disable it now.")
            return Result.failure()
        }

        if (FileDownloader.areDownloadsCurrentlyRunning()) {
            Log.i(LOG_TAG, "Retry background job because other downloads are running.")
            return Result.retry()
        }

        if (!BackgroundSettingsHelper.isUpdateCheckOnMeteredAllowed && isNetworkMetered(context)) {
            Log.i(LOG_TAG, "No unmetered network available for update check.")
            return Result.retry()
        }

        return null
    }

    private suspend fun checkForOutdatedApps(): List<AppAndUpdateStatus> {
        DataStoreHelper.lastBackgroundCheck = ZonedDateTime.now()
        val appsAndUpdateStatus = App.values()
            // simple and fast checks
            .filter { it !in BackgroundSettingsHelper.excludedAppsFromUpdateCheck }
            .filter { DeviceAbiExtractor.supportsOneOf(it.impl.supportedAbis) }
            .filter { it.impl.isInstalled(context) == InstallationStatus.INSTALLED }
            // query latest available update
            .map {
                val updateStatus = it.impl.findAppUpdateStatus(context, USE_CACHE)
                AppAndUpdateStatus(it, updateStatus)
            }

        // delete old cached APK files
        appsAndUpdateStatus.forEach { (app, appUpdateStatus) ->
            app.downloadedFileCache.deleteAllExceptLatestApkFile(context, appUpdateStatus.latestUpdate)
        }

        // return outdated apps with available updates
        return appsAndUpdateStatus
            .filter { (_, appUpdateStatus) -> appUpdateStatus.isUpdateAvailable }
    }

    private fun shouldDownloadsBeAborted(): Result? {
        if (!BackgroundSettingsHelper.isDownloadEnabled) {
            Log.i(LOG_TAG, "Don't download updates because the user don't want it.")
            return Result.retry()
        }

        if (!BackgroundSettingsHelper.isDownloadOnMeteredAllowed && isNetworkMetered(context)) {
            Log.i(LOG_TAG, "No unmetered network available for download.")
            return Result.retry()
        }

        if (DeviceSdkTester.supportsAndroidNougat()) {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (manager.restrictBackgroundStatus == RESTRICT_BACKGROUND_STATUS_ENABLED) {
                Log.i(LOG_TAG, "Data Saver is enabled. Do not download updates in the background.")
                return Result.retry()
            }
        }

        return null
    }

    @MainThread
    private suspend fun downloadUpdateAndReturnAvailability(app: App, updateStatus: AppUpdateStatus): Boolean {
        val latestUpdate = updateStatus.latestUpdate
        if (app.downloadedFileCache.isApkFileCached(context, latestUpdate)) {
            Log.i(LOG_TAG, "Skip $app download because it's already cached.")
            return true
        }

        Log.i(LOG_TAG, "Download update for $app.")
        val url = latestUpdate.downloadUrl
        val file = app.downloadedFileCache.getApkOrZipTargetFileForDownload(context, latestUpdate)
        return try {
            // run async with await later
            // TODO: two caches for the same file?
            val (deferred, channel) = FileDownloader.downloadFile(url, file)
            BackgroundNotificationBuilder.showDownloadRunningNotification(context, app, null, null)
            for (progress in channel) {
                BackgroundNotificationBuilder.showDownloadRunningNotification(
                    context, app, progress.progressInPercent, progress.totalMB
                )
            }

            deferred.await()

            if (app.impl.isAppPublishedAsZipArchive()) {
                app.downloadedFileCache.extractApkFromZipArchive(context, latestUpdate)
                app.downloadedFileCache.deleteZipFile(context)
            }

            // I suspect that sometimes the server offers the wrong file for download
            ApkChecker.throwIfDownloadedFileHasDifferentSize(file, latestUpdate)
            val apkFile = app.downloadedFileCache.getApkFile(context, latestUpdate)
            ApkChecker.throwIfApkFileIsNoValidZipFile(apkFile)

            BackgroundNotificationRemover.removeDownloadRunningNotification(context, app)
            app.downloadedFileCache.deleteAllExceptLatestApkFile(context, latestUpdate)
            true
        } catch (e: DisplayableException) {
            BackgroundNotificationRemover.removeDownloadRunningNotification(context, app)
            BackgroundNotificationBuilder.showDownloadNotification(context, app, e)
            app.downloadedFileCache.deleteAllApkFileForThisApp(context)
            false
        }
    }

    private fun shouldInstallationBeAborted(): Result? {
        if (DeviceSdkTester.supportsAndroid10() && !context.packageManager.canRequestPackageInstalls()) {
            Log.i(LOG_TAG, "Missing installation permission")

            return Result.success()
        }

        if (!BackgroundSettingsHelper.isInstallationEnabled) {
            Log.i(LOG_TAG, "Automatic background app installation is not enabled.")
            return Result.success()
        }

        if (!DeviceSdkTester.supportsAndroid12() && InstallerSettingsHelper.getInstallerMethod() == SESSION_INSTALLER) {
            Log.i(LOG_TAG, "The current installer can not update apps in the background")
            return Result.success()
        }

        if (InstallerSettingsHelper.getInstallerMethod() == NATIVE_INSTALLER) {
            Log.i(LOG_TAG, "The current installer can not update apps in the background")
            return Result.success()
        }

        return null
    }

    private suspend fun installApplication(app: App, updateStatus: AppUpdateStatus) {
        val file = app.downloadedFileCache.getApkFile(context, updateStatus.latestUpdate)
        require(file.exists()) { "AppCache has no cached APK file" }

        val installer = createBackgroundAppInstaller(context, app)
        try {
            installer.startInstallation(context, file)

            BackgroundNotificationBuilder.showInstallSuccessNotification(context, app)
            app.impl.appIsInstalledCallback(context, updateStatus)

            if (BackgroundSettingsHelper.isDeleteUpdateIfInstallSuccessful) {
                app.downloadedFileCache.deleteAllApkFileForThisApp(context)
            }
        } catch (e: UserInteractionIsRequiredException) {
            BackgroundNotificationBuilder.showUpdateAvailableNotification(context, app)
            if (BackgroundSettingsHelper.isDeleteUpdateIfInstallFailed) {
                app.downloadedFileCache.deleteAllApkFileForThisApp(context)
            }
        } catch (e: InstallationFailedException) {
            val wrappedException = InstallationFailedException(
                "Failed to install ${app.name} in the background with ${installer.type}.", -532, e
            )
            BackgroundNotificationBuilder.showInstallFailureNotification(
                context, app, e.errorCode, e.translatedMessage, wrappedException
            )
            app.downloadedFileCache.deleteAllApkFileForThisApp(context)
        }
    }

    companion object {
        private const val WORK_MANAGER_KEY = "update_checker"
        private const val LOG_TAG = "BackgroundJob"
        private val MAX_RETRIES = getRetriesForTotalBackoffTime(Duration.ofHours(8))

        /**
         * Should be called when the user minimize the app to make sure that the background update check
         * is running.
         */
        fun initBackgroundUpdateCheck(context: Context) {
            if (BackgroundSettingsHelper.isUpdateCheckEnabled) {
                start(
                    context.applicationContext,
                    KEEP,
                    BackgroundSettingsHelper.updateCheckInterval,
                    BackgroundSettingsHelper.isUpdateCheckOnlyAllowedWhenDeviceIsIdle
                )
            } else {
                stop(context.applicationContext)
            }
        }

        fun forceRestartBackgroundUpdateCheck(context: Context) {
            if (BackgroundSettingsHelper.isUpdateCheckEnabled) {
                start(
                    context.applicationContext,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    BackgroundSettingsHelper.updateCheckInterval,
                    BackgroundSettingsHelper.isUpdateCheckOnlyAllowedWhenDeviceIsIdle
                )
            } else {
                stop(context.applicationContext)
            }
        }

        /**
         * Should be called when the user changes specific background settings.
         * If value is null, the value from SharedPreferences will be used.
         */
        fun changeBackgroundUpdateCheck(
            context: Context,
            enabled: Boolean,
            interval: Duration,
            onlyWhenIdle: Boolean,
        ) {
            if (enabled) {
                start(context.applicationContext, ExistingPeriodicWorkPolicy.UPDATE, interval, onlyWhenIdle)
            } else {
                stop(context.applicationContext)
            }
        }

        private fun start(
            context: Context,
            policy: ExistingPeriodicWorkPolicy,
            interval: Duration,
            onlyWhenIdle: Boolean,
        ) {
            val requiredNetworkType =
                if (BackgroundSettingsHelper.isUpdateCheckOnMeteredAllowed) CONNECTED else UNMETERED
            val builder = Constraints.Builder()
                .setRequiredNetworkType(requiredNetworkType)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)

            if (DeviceSdkTester.supportsAndroidMarshmallow()) {
                builder.setRequiresDeviceIdle(onlyWhenIdle)
            }

            val minutes = interval.toMinutes()
            val workRequest = PeriodicWorkRequest.Builder(BackgroundJob::class.java, minutes, MINUTES)
                .setConstraints(builder.build())
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_MANAGER_KEY, policy, workRequest)
        }

        private fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_MANAGER_KEY)
        }

        private fun calcBackoffTime(runAttempts: Int): Duration {
            val unlimitedBackoffTime = Math.scalb(DEFAULT_BACKOFF_DELAY_MILLIS.toDouble(), runAttempts)
            val limitedBackoffTime = unlimitedBackoffTime.coerceIn(
                WorkRequest.MIN_BACKOFF_MILLIS.toDouble(),
                WorkRequest.MAX_BACKOFF_MILLIS.toDouble()
            )
            return Duration.ofMillis(limitedBackoffTime.toLong())
        }

        private fun getRetriesForTotalBackoffTime(totalTime: Duration): Int {
            var totalTimeMs = 0L
            repeat(1000) { runAttempt -> // runAttempt is zero-based
                totalTimeMs += calcBackoffTime(runAttempt).toMillis()
                if (totalTimeMs >= totalTime.toMillis()) {
                    return runAttempt + 1
                }
            }
            throw RuntimeException("Endless loop")
        }
    }
}