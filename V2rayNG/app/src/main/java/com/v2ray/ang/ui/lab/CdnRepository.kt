package com.v2ray.ang.ui.lab

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.v2ray.ang.R
import com.v2ray.ang.core.cdn.*
import com.v2ray.ang.core.lab.LabRecords
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.cdnFeedClient
import com.v2ray.ang.handler.fetchCdnFeed
import com.v2ray.ang.handler.cdnFeedProxyPort
import com.v2ray.ang.handler.CdnFeedException
import com.v2ray.ang.handler.CdnFeedIssue
import com.v2ray.ang.handler.cdnFeedUrl
import android.os.SystemClock
import com.v2ray.ang.helper.MessageHelper
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CdnProfile(val guid: String, val name: String)
internal data class CdnLive(val run: CdnRun?, val network: Long)
internal data class CdnData(val profiles: List<CdnProfile>, val feedAt: Long?, val live: CdnLive,
    val feedUrl: String = CdnPolicy.FEED, val feedIssue: Int? = null)
internal class CdnFailure(val text: Int, val code: Int? = null) : IllegalStateException()
internal interface CdnDataSource {
    suspend fun load(): CdnData
    suspend fun live(): CdnLive
    suspend fun update()
    suspend fun prepare(guid: String, download: Boolean, budget: Int, manualIps: String = ""): CdnRequest
    suspend fun setFeedUrl(url: String)
    suspend fun send(request: CdnRequest?)
    suspend fun save(runId: String): String
    suspend fun copy(runId: String, address: String?): Boolean = false
}
internal class CdnRepository(private val context: Context) : CdnDataSource {
    override suspend fun load(): CdnData = withContext(Dispatchers.IO) {
        val profiles = MmkvManager.decodeAllServerList().distinct().mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.takeIf(CdnPolicy::edgeSupported)?.let { CdnProfile(guid, it.remarks) }
        }
        val raw = MmkvManager.readCdnFeed()
        var issue: Int? = null
        val date = raw?.let {
            try { catalog(it).generatedAt } catch (e: CdnFailure) {
                issue = e.text
                com.v2ray.ang.util.LogUtil.e(com.v2ray.ang.AppConfig.TAG, "CDN mode=UI phase=cache-validation", IllegalStateException(e.javaClass.simpleName))
                null
            }
        }
        CdnData(profiles, date, live(), MmkvManager.readCdnFeedUrl(), issue)
    }
    override suspend fun live(): CdnLive = withContext(Dispatchers.IO) {
        CdnLive(MmkvManager.readCdnRun(), physicalHandle())
    }
    private fun physicalHandle(): Long {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        val caps = active?.let(cm::getNetworkCapabilities)
        // Without evidence about a VPN's underlying network, applying a result is deliberately blocked.
        return if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) active.networkHandle else 0L
    }
    override suspend fun update() = withContext(Dispatchers.IO) {
        val url = try { cdnFeedUrl(MmkvManager.readCdnFeedUrl()) }
            catch (_: IllegalArgumentException) { throw CdnFailure(R.string.cdn_source_invalid) }
        val snapshot = MessageHelper.requestConnectionSnapshot(context)
        val port = cdnFeedProxyPort(snapshot, SystemClock.elapsedRealtime())
        val client = cdnFeedClient(port)
        try {
            val raw = fetchCdnFeed(client, url)
            catalog(raw)
            if (port != null) {
                val current = MessageHelper.requestConnectionSnapshot(context)
                if (current?.sessionId != snapshot?.sessionId || cdnFeedProxyPort(current, SystemClock.elapsedRealtime()) != port)
                    throw CdnFailure(R.string.cdn_feed_network)
            }
            if (MmkvManager.readCdnFeedUrl() != url) throw CdnFailure(R.string.cdn_changed)
            try { MmkvManager.saveCdnFeed(raw) }
            catch (_: com.v2ray.ang.handler.ProfileStorageException) { throw CdnFailure(R.string.cdn_feed_storage) }
        } catch (e: CdnFeedException) {
            throw CdnFailure(when (e.issue) {
                CdnFeedIssue.HTTP -> R.string.cdn_feed_http
                CdnFeedIssue.TLS -> R.string.cdn_feed_tls
                CdnFeedIssue.BODY -> R.string.cdn_feed_format
                else -> R.string.cdn_feed_network
            }, e.httpCode)
        } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    private suspend fun catalog(raw: String): CdnCatalog = withContext(Dispatchers.Default) {
        try { CdnCatalog(raw) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: CdnCatalogException) { throw CdnFailure(if (e.issue == CdnCatalogIssue.OLD) R.string.cdn_feed_old else R.string.cdn_feed_clock) }
        catch (_: Exception) { throw CdnFailure(R.string.cdn_feed_format) }
    }
    override suspend fun setFeedUrl(url: String) = withContext(Dispatchers.IO) {
        try { MmkvManager.saveCdnFeedUrl(url) }
        catch (_: IllegalArgumentException) { throw CdnFailure(R.string.cdn_source_invalid) }
    }
    override suspend fun prepare(guid: String, download: Boolean, budget: Int, manualIps: String): CdnRequest = withContext(Dispatchers.IO) {
        if (SettingsManager.isRootMode()) throw CdnFailure(R.string.cdn_unsupported)
        val network = physicalHandle().takeIf { it != 0L } ?: throw CdnFailure(R.string.cdn_start_network)
        val profile = MmkvManager.decodeServerConfig(guid) ?: throw CdnFailure(R.string.cdn_changed)
        if (!CdnPolicy.edgeSupported(profile)) throw CdnFailure(R.string.cdn_unsupported)
        if (manualIps.isNotBlank()) {
            val addresses = try { CdnManualIps.parse(manualIps) }
                catch (_: IllegalArgumentException) { throw CdnFailure(R.string.cdn_manual_invalid) }
            return@withContext CdnRequest(UUID.randomUUID().toString(), guid, LabRecords.revision(profile), "manual",
                addresses, false, addresses.size, networkHandle = network, edgeScan = true, manual = true).also { it.validate() }
        }
        val raw = MmkvManager.readCdnFeed() ?: throw CdnFailure(R.string.cdn_need_feed)
        val catalog = catalog(raw)
        val address = profile.server ?: throw CdnFailure(R.string.cdn_unknown)
        // Explicit preview action only. DNS is identification evidence, not a DNS leak/privacy test.
        val addresses = if (CdnPrefix.ipv4(address) != null) listOf(address)
            else InetAddress.getAllByName(address).map { it.hostAddress.orEmpty() }
        val provider = catalog.provider(addresses) ?: throw CdnFailure(R.string.cdn_unknown)
        CdnRequest(UUID.randomUUID().toString(), guid, LabRecords.revision(profile), provider,
            withContext(Dispatchers.Default) { catalog.candidates(provider) }, false, budget,
            catalog.ranges.getValue(provider).map { it.cidr() }, network, edgeScan = true).also { it.validate() }
    }
    override suspend fun send(request: CdnRequest?) = withContext(Dispatchers.IO) { MessageHelper.sendCdnCommand(context, request) }
    override suspend fun copy(runId: String, address: String?): Boolean {
        val text = withContext(Dispatchers.IO) {
            val run = MmkvManager.readCdnRun()?.takeIf { it.request.id == runId } ?: throw CdnFailure(R.string.cdn_changed)
            val eligible = run.edgeAddresses()
            val addresses = if (address == null) eligible else eligible.filter { it == address }
            if (addresses.isEmpty()) throw CdnFailure(R.string.cdn_changed)
            addresses.joinToString("\n")
        }
        return withContext(Dispatchers.Main) {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.cdn_edge_copy_all), text))
            true
        }
    }
    override suspend fun save(runId: String): String = withContext(Dispatchers.IO) {
        val live = live()
        val run = live.run ?: throw CdnFailure(R.string.cdn_changed)
        if (run.request.id != runId || !run.canSave(System.currentTimeMillis(), live.network)) throw CdnFailure(R.string.cdn_save_network)
        val created = MmkvManager.createCdnTrial(run.request.guid, run.request.revision, requireNotNull(run.best()?.address), context.getString(R.string.cdn_trial)) {
            val latest = MmkvManager.readCdnRun()
            if (latest?.request?.id != runId || !latest.canSave(System.currentTimeMillis(), physicalHandle()))
                throw CdnFailure(R.string.cdn_save_network)
        }
        com.v2ray.ang.handler.SettingsChangeManager.makeSetupGroupTab()
        created
    }
}
