package com.v2ray.ang.ui.lab

import android.content.Context
import com.v2ray.ang.R
import com.v2ray.ang.core.lab.*
import com.v2ray.ang.core.health.*
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LabGroup(val id: String, val name: String)
internal data class LabProfile(val candidate: LabCandidate, val name: String, val supported: Boolean)
internal data class LabData(val groups: List<LabGroup>, val profiles: List<LabProfile>, val runs: List<LabRunRecord>,
    val healthRuns: List<HealthRun> = emptyList(), val healthUnavailable: Boolean = false)

internal interface LabDataSource {
    suspend fun load(groupId: String): LabData
    suspend fun sendHealth(request: HealthRequest?)
    suspend fun send(request: LabStartRequest?)
}

internal class LabRepository(private val context: Context) : LabDataSource {
    override suspend fun load(groupId: String): LabData = withContext(Dispatchers.IO) {
        val localized = AppLocaleManager.localizedContext(context)
        val groups = listOf(LabGroup("", localized.getString(R.string.filter_config_all))) +
            MmkvManager.decodeSubscriptions().filter { it.guid.isNotEmpty() }.map { LabGroup(it.guid, it.subscription.remarks) }
        val guids = if (groupId.isEmpty()) MmkvManager.decodeAllServerList() else MmkvManager.decodeServerList(groupId)
        val profiles = guids.distinct().mapNotNull { guid -> MmkvManager.decodeServerConfig(guid)?.let {
            LabProfile(LabCandidate(guid, LabRecords.revision(it), LabTransport.of(it)), it.remarks, LabRecords.supported(it))
        } }
        var healthUnavailable = false
        val health = try { MmkvManager.readHealthRuns() } catch (e: Exception) {
            healthUnavailable = true
            LogUtil.e(AppConfig.TAG, "Health mode=UI phase=load-history", IllegalStateException(e.javaClass.simpleName))
            emptyList()
        }
        LabData(groups, profiles, MmkvManager.readLaboratoryRuns(), health, healthUnavailable)
    }

    override suspend fun sendHealth(request: HealthRequest?) = withContext(Dispatchers.IO) {
        MessageHelper.sendHealthCommand(context, request)
    }

    override suspend fun send(request: LabStartRequest?) = withContext(Dispatchers.IO) {
        MessageHelper.sendLaboratoryCommand(context, request)
    }
}
