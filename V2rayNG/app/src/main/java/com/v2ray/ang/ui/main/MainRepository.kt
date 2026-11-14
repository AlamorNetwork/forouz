package com.v2ray.ang.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.v2ray.ang.dto.ConnectionSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import com.v2ray.ang.dto.AwgConnectionTestRequest
import com.v2ray.ang.helper.ConnectionProbeReply
import com.v2ray.ang.helper.requestConnectionProbe
import com.v2ray.ang.helper.ProbeReplyWindow

class MainRepository(
    private val app: AngApplication
) : MainDataSource {

    override suspend fun measurePrivacy(target: PrivacyTarget): com.v2ray.ang.core.privacy.PrivacyProbeReply {
        fun current() = latestConnection.value?.takeIf { it.running }?.let {
            PrivacyTarget(it.profileGuid, it.sessionId, it.privacyRoute, it.privacyProxyPort)
        } ?: PrivacyTarget()
        if (current() != target) return com.v2ray.ang.core.privacy.PrivacyProbeReply.Unavailable(
            com.v2ray.ang.core.privacy.PrivacyIssue.NETWORK_CHANGED)
        val result = com.v2ray.ang.helper.PrivacyNetworkProbe(app).measure(target.vpn, target.route, target.proxyPort)
        return if (current() == target) result else com.v2ray.ang.core.privacy.PrivacyProbeReply.Unavailable(
            com.v2ray.ang.core.privacy.PrivacyIssue.NETWORK_CHANGED)
    }

    override suspend fun createFragmentVariant(guid: String, preset: com.v2ray.ang.core.FragmentPreset): String? {
        // Owner calls on IO; re-read by GUID rather than trusting a stale card.
        val source = MmkvManager.decodeServerConfig(guid) ?: return null
        val label = localizedContext.getString(fragmentPresetLabel(preset))
        val name = localizedContext.getString(R.string.lab_fragment_variant_name, source.remarks, label)
        val variant = com.v2ray.ang.core.XrayFragmentVariants.create(source, preset, name, System.currentTimeMillis())
            ?: return null
        return MmkvManager.createLocalTrial(variant)
    }

    private val localizedContext: Context
        get() = AppLocaleManager.localizedContext(app)

    private val closed = AtomicBoolean(false)

    // Probe results are finite and must remain lossless until the ViewModel coalesces them.
    private val mainServiceEventChannel = Channel<MainServiceEvent>(Channel.UNLIMITED)

    override val mainServiceEvent: Flow<MainServiceEvent> = mainServiceEventChannel.receiveAsFlow()
    private val latestConnection = MutableStateFlow<ConnectionSnapshot?>(null)
    // Telemetry is conflated separately, so slow consumers cannot queue samples or drop probes.
    override val connectionSnapshots = latestConnection.asStateFlow()
    private val managedProbeIds = ProbeReplyWindow()

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val safeIntent = intent ?: return
            if (safeIntent.getIntExtra("key", 0) == AppConfig.MSG_CONNECTION_SNAPSHOT) {
                safeIntent.serializable<ConnectionSnapshot>("content")?.let { latestConnection.value = it }
                return
            }
            val requestId = safeIntent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID).orEmpty()
            if (requestId in managedProbeIds && safeIntent.getIntExtra("key", 0) in
                setOf(AppConfig.MSG_MEASURE_DELAY_RESULT, AppConfig.MSG_MEASURE_DELAY_CANCEL)) return
            val event = when (safeIntent.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> MainServiceEvent.StateRunning
                AppConfig.MSG_STATE_NOT_RUNNING -> MainServiceEvent.StateNotRunning
                AppConfig.MSG_STATE_START_SUCCESS -> MainServiceEvent.StateStartSuccess
                AppConfig.MSG_STATE_START_FAILURE -> MainServiceEvent.StateStartFailure(
                    safeIntent.getStringExtra("content")
                )

                AppConfig.MSG_STATE_STOP_SUCCESS -> MainServiceEvent.StateStopSuccess
                AppConfig.MSG_MEASURE_DELAY_RESULT -> safeIntent
                    .serializable<ConnectionTestResult>("content")
                    ?.let { MainServiceEvent.MeasureDelayResult(it, requestId) }
                AppConfig.MSG_MEASURE_DELAY_CANCEL -> MainServiceEvent.MeasureDelayCancelled(requestId)

                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> safeIntent
                    .serializable<RealPingResult>("content")
                    ?.let { MainServiceEvent.MeasureConfigSuccess(it, requestId) }
                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> MainServiceEvent.MeasureConfigNotify(
                    safeIntent.getStringExtra("content").orEmpty(), requestId
                )

                AppConfig.MSG_MEASURE_CONFIG_FINISH -> MainServiceEvent.MeasureConfigFinish(
                    requestId
                )
                AppConfig.MSG_MEASURE_CONFIG_CANCEL -> MainServiceEvent.MeasureConfigCancelled(requestId)

                else -> null
            }
            event?.let { mainServiceEventChannel.trySend(it) }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            serviceReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            // Every sender of this action is in our UID, including the daemon process.
            // Runtime proxy admission must not accept another app's forged snapshot.
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshServiceState()
    }

    override fun refreshServiceState() {
        MessageHelper.sendMsg2ServiceForResult(app, AppConfig.MSG_REGISTER_CLIENT, "") { handled ->
            if (!handled && !closed.get()) mainServiceEventChannel.trySend(MainServiceEvent.StateNotRunning)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            MessageHelper.sendMsg2Service(app, AppConfig.MSG_UNREGISTER_CLIENT, "")
        }.onFailure {
            LogUtil.e(AppConfig.TAG, "Failed to unregister service client", it)
        }
        runCatching {
            app.unregisterReceiver(serviceReceiver)
        }.onFailure {
            LogUtil.e(AppConfig.TAG, "Failed to unregister main service receiver", it)
        }
        mainServiceEventChannel.close()
    }

    override fun getSelectedSubscriptionId(): String =
        MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()

    override fun setSelectedSubscriptionId(id: String) {
        MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, id)
    }

    override fun getSelectServer(): String? = MmkvManager.getSelectServer()

    override fun setSelectServer(guid: String) = MmkvManager.setSelectServer(guid)

    override fun getConfirmRemove(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE, false)

    override fun getDoubleColumnDisplay(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)

    override fun isGroupAllDisplayEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_GROUP_ALL_DISPLAY)

    override fun getString(resId: Int): String = localizedContext.getString(resId)

    override fun getString(resId: Int, vararg formatArgs: Any): String =
        localizedContext.getString(resId, *formatArgs)

    override fun getSubscriptions(): List<SubscriptionCache> {
        val result = mutableListOf<SubscriptionCache>()
        if (isGroupAllDisplayEnabled()) {
            result += SubscriptionCache(
                guid = "",
                subscription = SubscriptionItem().apply {
                    remarks = localizedContext.getString(R.string.filter_config_all)
                }
            )
        }
        result += MmkvManager.decodeSubscriptions()
        return result
    }

    override fun getSubscriptionItem(id: String): SubscriptionItem? =
        MmkvManager.decodeSubscription(id)

    override fun getServerGuidList(groupId: String): List<String> =
        if (groupId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else {
            MmkvManager.decodeServerList(groupId)
        }

    override fun decodeServerConfig(guid: String): ProfileItem? =
        MmkvManager.decodeServerConfig(guid)

    override fun decodeAffiliationInfo(guid: String): ServerAffiliationInfo? =
        MmkvManager.decodeServerAffiliationInfo(guid)

    override fun encodeServerList(guids: List<String>, groupId: String) =
        MmkvManager.encodeServerList(ArrayList(guids), groupId)

    override fun removeServer(guid: String) = MmkvManager.removeServer(guid)

    override fun removeAllServer(): Int = MmkvManager.removeAllServer()

    override fun removeInvalidServerByGuid(guid: String): Int =
        MmkvManager.removeInvalidServer(guid)

    override fun removeInvalidServersInGroup(groupId: String): Int =
        if (groupId.isEmpty()) {
            MmkvManager.removeInvalidServer("")
        } else {
            getServerGuidList(groupId).sumOf(::removeInvalidServerByGuid)
        }

    override fun clearAllTestDelayResults(guids: List<String>) =
        MmkvManager.clearAllTestDelayResults(guids)

    override fun sortByTestResultsForSub(subId: String) {
        AngConfigManager.sortByTestResultsForSub(subId)
    }

    override fun getSubsList(): List<String> = MmkvManager.decodeSubsList()

    override fun inspectImport(text: String): MainImportInspection = inspectProfileImport(text) { Utils.decode(it).orEmpty() }

    override suspend fun importBatchConfig(
        server: String?,
        subscriptionId: String,
        updateUI: Boolean
    ): Pair<Int, Int> = AngConfigManager.importBatchConfig(server, subscriptionId, updateUI)

    override suspend fun resumeAmneziaImport(
        challenge: com.v2ray.ang.core.amnezia.AmneziaGateway.CaptchaRequired,
        answer: String, subscriptionId: String
    ): Pair<Int, Int> = AngConfigManager.importBatchConfig(challenge.resume(answer), subscriptionId, true)

    override fun updateConfigViaSubAll(): SubscriptionUpdateResult =
        AngConfigManager.updateConfigViaSubAll()

    override fun updateConfigViaSub(subscriptionCache: SubscriptionCache): SubscriptionUpdateResult =
        AngConfigManager.updateConfigViaSub(subscriptionCache)

    override fun shareNonCustomConfigsToClipboard(guids: List<String>): Int =
        AngConfigManager.shareNonCustomConfigsToClipboard(app, guids)

    override fun share2QRCode(guid: String): android.graphics.Bitmap? =
        AngConfigManager.share2QRCode(guid)

    override fun share2Clipboard(guid: String): Boolean =
        AngConfigManager.share2Clipboard(app, guid) == 0

    override suspend fun copyAmneziaNative(guid: String): Boolean {
        return try {
            val raw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (MmkvManager.decodeServerConfig(guid)?.configType != com.v2ray.ang.enums.EConfigType.AMNEZIAWG) null
                else MmkvManager.decodeServerRaw(guid)
            } ?: return false
            val native = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.v2ray.ang.fmt.AmneziaWgFmt.exportNative(raw)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                com.v2ray.ang.util.Utils.setSensitiveClipboard(app, app.getString(com.v2ray.ang.R.string.awg_copy_native), native)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            com.v2ray.ang.util.LogUtil.e(message = "Native AWG export failed for profile $guid (${e.javaClass.simpleName})")
            false
        }
    }

    override fun sendMsg2Service(msgId: Int, content: String) =
        MessageHelper.sendMsg2Service(app, msgId, content)

    override fun sendMsg2TestService(msg: TestServiceMessage, requestId: String?) =
        MessageHelper.sendMsg2TestService(app, msg, requestId)

    override fun cancelAllPing() {
        sendMsg2TestService(
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
    }

    override suspend fun testCurrentServerRealPing(requestId: String, profileGuid: String) {
        managedProbeIds.track(requestId)
        val awg = MmkvManager.decodeServerConfig(profileGuid)?.configType == com.v2ray.ang.enums.EConfigType.AMNEZIAWG
        val reply = if (awg) requestConnectionProbe(app, AppConfig.MSG_MEASURE_AWG_DELAY, requestId,
            AwgConnectionTestRequest(requestId, profileGuid), cancelCommand = AppConfig.MSG_CANCEL_AWG_DELAY)
        else requestConnectionProbe(app, AppConfig.MSG_MEASURE_DELAY, requestId, requestId, timeoutMillis = 30_000)
        val result = when (reply) {
            is ConnectionProbeReply.Result -> reply.value
            ConnectionProbeReply.Unavailable -> ConnectionTestResult(-1, localizedContext.getString(R.string.lab_test_not_active))
            ConnectionProbeReply.TimedOut -> ConnectionTestResult(-1, localizedContext.getString(R.string.lab_test_reply_timeout))
        }
        mainServiceEventChannel.trySend(MainServiceEvent.MeasureDelayResult(result, requestId))
    }

    override fun syncSubscriptions() {
        SubscriptionUpdater.sync(app)
    }

    override fun initAssets() {
        SettingsManager.initAssets(app, app.assets)
    }
}
