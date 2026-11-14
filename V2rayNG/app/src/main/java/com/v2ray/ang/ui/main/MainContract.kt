package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget

/** Locale-neutral state formatted only when it reaches the main UI. */
sealed interface MainStatus {
    data object Disconnected : MainStatus
    data object Connected : MainStatus
    data object Testing : MainStatus
    data class TestProgress(val progress: String) : MainStatus
    data class ConnectionTest(val result: ConnectionTestResult) : MainStatus
}

/**
 * Main UI state
 */
data class MainUiState(
    val privacy: MainPrivacyState = MainPrivacyState(),
    val connection: MainConnectionState = MainConnectionState(),
    val connectedSeconds: Long = 0,
    val connectionKnown: Boolean = connection.snapshot != null,
    val homeProfile: MainHomeProfile? = null,
    val activeHomeProfile: MainHomeProfile? = null,
    val connectivity: MainConnectivityCheck? = null,
    val observedAt: Long = 0,
    val importReview: MainImportReview? = null,
    val restartPromptGuid: String? = null,
    val isInspectingImport: Boolean = false,
    val isCurrentTesting: Boolean = false,
    val pendingCommand: MainPendingCommand? = null,
    val unconfirmedCommand: MainPendingCommand? = null,
    val deferredStopUntil: Long = 0,
    val connectionIssue: MainConnectionIssue? = null,
    val amneziaCaptchaImage: android.graphics.Bitmap? = null,
    val groups: List<GroupMapItem> = emptyList(),
    val selectedGroupId: String = "",
    val searchQuery: String = "",
    val selectedGuid: String? = null,
    val isRunning: Boolean = false,
    val isTesting: Boolean = false,
    val status: MainStatus = MainStatus.Disconnected,
    val locateTarget: LocateTarget? = null,
    val confirmRemove: Boolean = false,
    val doubleColumnDisplay: Boolean = false,
    val shareQRCodeBitmap: android.graphics.Bitmap? = null
)

/**
 * All possible user interaction intents
 */
sealed interface MainAction {
    data object RunPrivacyCheck : MainAction
    data object RequestPrivacyBaseline : MainAction
    data object ConfirmPrivacyBaseline : MainAction
    data object DismissPrivacyBaseline : MainAction
    data object ClearPrivacyBaseline : MainAction
    data object CancelPrivacyCheck : MainAction
    data class PrivacyOverviewVisible(val visible: Boolean) : MainAction

    data class CreateFragmentVariant(val guid: String, val preset: com.v2ray.ang.core.FragmentPreset) : MainAction
    data class ServiceCommandIssued(val kind: MainCommandKind) : MainAction
    data object VpnPermissionDenied : MainAction
    data object RequestRestartConfirmation : MainAction
    data object DismissRestartConfirmation : MainAction
    data object CancelServiceStart : MainAction
    data object RefreshConnectionState : MainAction
    data class CopyAmneziaNative(val guid: String) : MainAction
    data class SubmitAmneziaCaptcha(val answer: String) : MainAction
    data object DismissAmneziaCaptcha : MainAction
    data object Initialize : MainAction
    data object RefreshGroups : MainAction
    data object ToggleService : MainAction
    data object TestCurrentServer : MainAction
    data object TestAllServers : MainAction
    data object TestRealAllServers : MainAction
    data object CancelTesting : MainAction
    data object RemoveAllServers : MainAction
    data object RemoveDuplicateServers : MainAction
    data object RemoveInvalidServers : MainAction
    data object SortByTestResults : MainAction
    data object UpdateSubscriptions : MainAction
    data class UpdateGroupSubscription(val groupId: String) : MainAction
    data object ExportAll : MainAction

    data object ImportQRcode : MainAction
    data object ImportClipboard : MainAction
    data object ImportConfigLocal : MainAction
    data class ImportManually(val type: Int) : MainAction
    data object RestartService : MainAction
    data object LocateSelectedServer : MainAction
    data object LocateActiveProfile : MainAction

    data class SelectGroup(val groupId: String) : MainAction
    data class SelectServer(val guid: String) : MainAction
    data class RemoveServer(val guid: String) : MainAction
    data class EditServer(val guid: String, val profile: com.v2ray.ang.dto.entities.ProfileItem) : MainAction
    data class Search(val query: String) : MainAction
    data class ShareQRCode(val guid: String) : MainAction
    data class ShareClipboard(val guid: String) : MainAction
    data class ShareFullContent(val guid: String) : MainAction
    data object DismissQRCodeDialog : MainAction
    data class OpenSubscriptionLink(val url: String) : MainAction

    data class ImportBatchConfig(val configText: String) : MainAction
    data class ConfirmImport(val id: String) : MainAction
    data object DismissImportReview : MainAction

    data object LocateHandled : MainAction
}
