package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object UpdateCheckerManager {
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        var response = fetchForouzReleases(forouzUpdateClient())
        ensureActive()
        if (response.isNullOrEmpty()) {
            response = fetchForouzReleases(forouzUpdateClient(SettingsManager.getHttpPort(),
                SettingsManager.getSocksUsername(), SettingsManager.getSocksPassword()))
        }
        ensureActive()
        val releases = response?.takeIf { it.length <= 2_000_000 }
            ?.let { JsonUtil.fromJsonSafe(it, Array<GitHubRelease>::class.java) }
            ?: throw IllegalStateException("FOROUZ release response unavailable")
        ForouzReleasePolicy.select(releases.toList(), BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.toList(),
            if (BuildConfig.DISTRIBUTION == "F-Droid") "fdroid" else "playstore",
            BuildConfig.BUILD_TYPE, includePreRelease)
    }
}
