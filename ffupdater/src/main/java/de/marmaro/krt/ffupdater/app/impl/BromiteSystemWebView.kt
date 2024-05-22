package de.marmaro.krt.ffupdater.app.impl

import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.MainThread
import de.marmaro.krt.ffupdater.R
import de.marmaro.krt.ffupdater.app.App
import de.marmaro.krt.ffupdater.app.entity.DisplayCategory.*
import de.marmaro.krt.ffupdater.app.entity.LatestVersion
import de.marmaro.krt.ffupdater.device.ABI
import de.marmaro.krt.ffupdater.device.DeviceAbiExtractor
import de.marmaro.krt.ffupdater.network.exceptions.NetworkException
import de.marmaro.krt.ffupdater.network.file.CacheBehaviour
import de.marmaro.krt.ffupdater.network.github.GithubConsumer
import de.marmaro.krt.ffupdater.settings.DeviceSettingsHelper

/**
 * https://github.com/bromite/bromite/releases
 * https://api.github.com/repos/bromite/bromite/releases
 * https://www.apkmirror.com/apk/bromite/bromite-system-webview-2/
 */
@Keep
@Deprecated("latest release is too old")
object BromiteSystemWebView : AppBase() {
    override val app = App.BROMITE_SYSTEMWEBVIEW
    override val packageName = "org.bromite.webview"
    override val title = R.string.bromite_systemwebview__title
    override val description = R.string.bromite_systemwebview__description
    override val installationWarning = R.string.bromite__warning
    override val downloadSource = "GitHub"
    override val icon = R.drawable.ic_logo_bromite_systemwebview
    override val minApiLevel = Build.VERSION_CODES.LOLLIPOP
    override val supportedAbis = ARM32_ARM64_X86_X64
    override val eolReason = R.string.eol_reason__browser_no_longer_maintained
    override val signatureHash = "e1ee5cd076d7b0dc84cb2b45fb78b86df2eb39a3b6c56ba3dc292a5e0c3b9504"
    override val installableByUser = false
    override val projectPage = "https://github.com/bromite/bromite"
    override val displayCategory = listOf(GOOD_PRIVACY_BROWSER, EOL)
    override val hostnameForInternetCheck = "https://api.github.com"

    @MainThread
    @Throws(NetworkException::class)
    override suspend fun fetchLatestUpdate(context: Context, cacheBehaviour: CacheBehaviour): LatestVersion {
        val fileName = findFileName()
        val result = GithubConsumer.findLatestRelease(
            repository = Bromite.REPOSITORY,
            isValidRelease = { !it.isPreRelease },
            isSuitableAsset = { it.name == fileName },
            cacheBehaviour = cacheBehaviour,
            requireReleaseDescription = false,
        )
        return LatestVersion(
            downloadUrl = result.url,
            version = result.tagName,
            publishDate = result.releaseDate,
            exactFileSizeBytesOfDownload = result.fileSizeBytes,
            fileHash = null,
        )
    }

    private fun findFileName(): String {
        val fileName = when (DeviceAbiExtractor.findBestAbi(supportedAbis, DeviceSettingsHelper.prefer32BitApks)) {
            ABI.ARMEABI_V7A -> "arm_SystemWebView.apk"
            ABI.ARM64_V8A -> "arm64_SystemWebView.apk"
            ABI.X86 -> "x86_SystemWebView.apk"
            ABI.X86_64 -> "x64_SystemWebView.apk"
            else -> throw IllegalArgumentException("ABI is not supported")
        }
        return fileName
    }
}