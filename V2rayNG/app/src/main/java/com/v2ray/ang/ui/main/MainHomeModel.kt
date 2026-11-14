package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/** Home needs presentation fields, never credentials or the full raw configuration. */
data class MainHomeProfile(val guid: String, val name: String, val protocol: String, val isAmnezia: Boolean, val delayMillis: Long?)

internal fun MainUiState.isSelectedProfileRunning(): Boolean = isRunning && selectedGuid != null &&
    connection.snapshot?.let { it.running && it.profileGuid == selectedGuid } == true

internal fun MainUiState.canTestConnection(): Boolean = connectionKnown && !isTesting && pendingCommand == null && activeTestProfileGuid() != null

internal fun MainUiState.activeTestProfileGuid(): String? = connection.snapshot
    ?.takeIf { isRunning && it.running }?.profileGuid?.takeIf { it.isNotBlank() }

internal fun MainUiState.connectionTestAction(): MainAction =
    if (isTesting) MainAction.CancelTesting else MainAction.TestCurrentServer

internal fun homeProfile(guid: String, profile: ProfileItem, delayMillis: Long?): MainHomeProfile =
    MainHomeProfile(guid, profile.remarks,
        if (profile.configType == EConfigType.AMNEZIAWG) "AmneziaWG" else serverProtocolDescription(profile),
        profile.configType == EConfigType.AMNEZIAWG, delayMillis?.takeIf { it > 0 })

internal fun MainUiState.withHomeProfile(guid: String?, profile: MainHomeProfile?): MainUiState =
    if (selectedGuid == guid) copy(homeProfile = profile) else this
