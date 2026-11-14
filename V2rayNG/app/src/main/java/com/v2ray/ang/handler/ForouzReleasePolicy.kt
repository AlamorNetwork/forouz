package com.v2ray.ang.handler

import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UpdateStatus
import java.net.URI

/** Pure release identity/selection policy. Android transport and installer remain separate owners. */
object ForouzReleasePolicy {
    const val REPOSITORY = "AlamorNetwork/forouz"
    const val PAGE = "https://github.com/$REPOSITORY"
    const val RELEASES_PAGE = "$PAGE/releases"
    const val API = "https://api.github.com/repos/$REPOSITORY/releases?per_page=100"

    data class Version(val numbers: List<Long>, val pre: List<String>) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            numbers.zip(other.numbers).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
            if (pre.isEmpty() != other.pre.isEmpty()) return if (pre.isEmpty()) 1 else -1
            for (i in 0 until minOf(pre.size, other.pre.size)) {
                val a = pre[i]; val b = other.pre[i]
                val an = a.all { it in '0'..'9' }; val bn = b.all { it in '0'..'9' }
                val compared = when {
                    an && bn -> if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
                    an != bn -> if (an) -1 else 1
                    else -> a.compareTo(b)
                }
                if (compared != 0) return compared
            }
            return pre.size.compareTo(other.pre.size)
        }
    }

    fun version(value: String?): Version? {
        val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$")
            .matchEntire(value?.takeIf { it.length <= 160 } ?: return null) ?: return null
        val numbers = (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
        val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
        if (pre.any { it.length > 1 && it[0] == '0' && it.all { c -> c in '0'..'9' } }) return null
        return Version(numbers, pre)
    }

    fun select(releases: List<GitHubRelease>, current: String, abis: List<String>, distribution: String,
        buildType: String, includePreRelease: Boolean): CheckUpdateResult {
        val installed = version(current) ?: return CheckUpdateResult(false, status = UpdateStatus.INVALID_METADATA)
        val eligible = releases.filter { !it.draft && (includePreRelease || !it.prerelease) }
        val candidates = eligible.mapNotNull { release -> version(release.tagName)?.let { release to it } }
            .filter { includePreRelease || it.second.pre.isEmpty() }
        val latest = candidates.maxByOrNull { it.second }
            ?: return CheckUpdateResult(false, status = if (eligible.isEmpty()) UpdateStatus.NO_RELEASE else UpdateStatus.INVALID_METADATA)
        if (latest.second <= installed) return CheckUpdateResult(false, status = UpdateStatus.UP_TO_DATE)
        val release = latest.first
        val tag = release.tagName ?: return CheckUpdateResult(false, status = UpdateStatus.INVALID_METADATA)
        val assetPattern = Regex("^FOROUZ_(.+)-(playstore|fdroid)-(debug|release)_(arm64-v8a|armeabi-v7a|x86_64|x86|universal)\\.apk$")
        val assets = release.assets.orEmpty().filterNotNull().mapNotNull { asset ->
            val name = asset.name ?: return@mapNotNull null
            val parts = assetPattern.matchEntire(name) ?: return@mapNotNull null
            if (version(parts.groupValues[1])?.compareTo(latest.second) != 0 || parts.groupValues[2] != distribution ||
                parts.groupValues[3] != buildType || !trustedAsset(asset.browserDownloadUrl, tag, name)) return@mapNotNull null
            parts.groupValues[4] to asset
        }
        val compatible = (abis + "universal").distinct().firstNotNullOfOrNull { abi ->
            assets.filter { it.first == abi }.singleOrNull()?.second
        }
        return CheckUpdateResult(hasUpdate = compatible != null, latestVersion = tag.removePrefix("v"),
            releaseNotes = release.body.orEmpty().take(20_000), downloadUrl = compatible?.browserDownloadUrl,
            isPreRelease = release.prerelease || latest.second.pre.isNotEmpty(),
            status = if (compatible != null) UpdateStatus.AVAILABLE else UpdateStatus.NO_COMPATIBLE_APK)
    }

    private fun trustedAsset(value: String?, tag: String, name: String): Boolean = try {
        val uri = URI(value ?: "")
        uri.scheme == "https" && uri.host.equals("github.com", true) && uri.port == -1 && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path == "/$REPOSITORY/releases/download/$tag/$name"
    } catch (_: Exception) { false }
}
