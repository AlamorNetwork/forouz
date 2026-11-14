package com.v2ray.ang.handler

import com.google.gson.Gson
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UpdateStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ForouzReleasePolicyTest {
    private fun release(tag: String = "v1.1.0", abi: String = "arm64-v8a", flavor: String = "playstore", type: String = "release"): GitHubRelease {
        val name = "FOROUZ_${tag.removePrefix("v")}-${flavor}-${type}_${abi}.apk"
        return GitHubRelease(tag, "Release notes", listOf(GitHubRelease.Asset(name, "${ForouzReleasePolicy.PAGE}/releases/download/$tag/$name")))
    }
    private fun select(releases: List<GitHubRelease>, current: String = "1.0.0", abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
        flavor: String = "playstore", type: String = "release", pre: Boolean = false) =
        ForouzReleasePolicy.select(releases, current, abis, flavor, type, pre)

    @Test fun `semantic versions compare without crashing on testing tags`() {
        val versions = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.10.0", "2.0.0")
        for ((a,b) in versions.zipWithNext()) assertTrue(ForouzReleasePolicy.version(a)!! < ForouzReleasePolicy.version(b)!!, "$a < $b")
        assertEquals(0, ForouzReleasePolicy.version("v1.0.0+build.1")!!.compareTo(ForouzReleasePolicy.version("1.0.0+build.2")!!))
        assertNotNull(ForouzReleasePolicy.version("1.0.0-lab16"))
    }
    @Test fun `malformed or overflowing version never becomes an update`() {
        for (s in listOf("", "1.0", "01.0.0", "1.0.0-01", "1.0.0/evil", "1.0.0 ", "9999999999999999999999999.0.0")) assertNull(ForouzReleasePolicy.version(s), s)
        assertEquals(UpdateStatus.INVALID_METADATA, select(listOf(release()), current = "broken").status)
    }
    @Test fun `no release and equal or older release have different statuses`() {
        assertEquals(UpdateStatus.NO_RELEASE, select(emptyList()).status)
        assertEquals(UpdateStatus.UP_TO_DATE, select(listOf(release("v1.0.0"))).status)
        assertEquals(UpdateStatus.UP_TO_DATE, select(listOf(release("v0.9.0"))).status)
    }
    @Test fun `drafts and prereleases are filtered and semver chooses newest`() {
        assertEquals(UpdateStatus.NO_RELEASE, select(listOf(release().copy(draft = true))).status)
        val pre = release("v2.0.0-rc.1").copy(prerelease = true)
        assertEquals("1.1.0", select(listOf(pre, release())).latestVersion)
        assertEquals("2.0.0-rc.1", select(listOf(release(), pre), pre = true).latestVersion)
    }
    @Test fun `ABI order comes from supported ABIs and universal is only fallback`() {
        val arm64 = release().assets!!.single()
        val arm32 = release(abi = "armeabi-v7a").assets!!.single()
        val all = release(abi = "universal").assets!!.single()
        val r = release().copy(assets = listOf(all, arm32, arm64))
        assertEquals(arm64.browserDownloadUrl, select(listOf(r)).downloadUrl)
        assertEquals(arm32.browserDownloadUrl, select(listOf(r), abis = listOf("armeabi-v7a")).downloadUrl)
        assertEquals(all.browserDownloadUrl, select(listOf(r), abis = listOf("x86_64")).downloadUrl)
    }
    @Test fun `distribution and build type cannot cross install`() {
        assertEquals(UpdateStatus.NO_COMPATIBLE_APK, select(listOf(release(flavor = "fdroid"))).status)
        assertEquals(UpdateStatus.NO_COMPATIBLE_APK, select(listOf(release(type = "debug"))).status)
        assertTrue(select(listOf(release(flavor = "fdroid")), flavor = "fdroid").hasUpdate)
        assertTrue(select(listOf(release(type = "debug")), type = "debug").hasUpdate)
    }
    @Test fun `external upstream insecure or redirected asset URLs are rejected`() {
        val original = release()
        val asset = original.assets!!.single()
        val badUrls = listOf(asset.browserDownloadUrl!!.replace("https:", "http:"),
            asset.browserDownloadUrl.replace("AlamorNetwork/forouz", "2dust/v2rayNG"),
            asset.browserDownloadUrl.replace("github.com", "github.com.evil.invalid"),
            asset.browserDownloadUrl + "?redirect=1", asset.browserDownloadUrl + "#fragment",
            asset.browserDownloadUrl.replace("github.com", "user@github.com"))
        for (url in badUrls) assertEquals(UpdateStatus.NO_COMPATIBLE_APK, select(listOf(original.copy(assets = listOf(asset.copy(browserDownloadUrl = url))))).status)
    }
    @Test fun `wrong file version extension or duplicate candidates cannot silently download`() {
        val r = release()
        val a = r.assets!!.single()
        val wrongVersion = r.copy(assets = listOf(a.copy(name = a.name!!.replace("1.1.0", "1.0.0"))))
        val wrongExtension = r.copy(assets = listOf(a.copy(name = a.name + ".zip")))
        val duplicate = r.copy(assets = listOf(a, a))
        for (entry in listOf(wrongVersion, wrongExtension, duplicate)) assertFalse(select(listOf(entry)).hasUpdate)
    }
    @Test fun `old GitHub JSON shape still reads and missing fields are not fake success`() {
        val r = Gson().fromJson("""{"tag_name":"v1.1.0","body":"notes","prerelease":false,"assets":[]}""", GitHubRelease::class.java)
        assertEquals(UpdateStatus.NO_COMPATIBLE_APK, select(listOf(r)).status)
        assertEquals(UpdateStatus.INVALID_METADATA, select(listOf(Gson().fromJson("{}", GitHubRelease::class.java))).status)
        assertEquals(UpdateStatus.NO_COMPATIBLE_APK, select(listOf(release().copy(assets = null))).status)
    }
    @Test fun `policy never uses upstream repository as a fallback`() {
        assertEquals("AlamorNetwork/forouz", ForouzReleasePolicy.REPOSITORY)
        assertTrue(ForouzReleasePolicy.API.startsWith("https://api.github.com/repos/AlamorNetwork/forouz/releases"))
    }
}
