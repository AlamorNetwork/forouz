package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainImportMenuTest {

    @Test
    fun regularShareMenuContainsOnlyShareActions() {
        val expected = listOf(
            ServerMenuAction.ShareQRCode,
            ServerMenuAction.ShareClipboard,
            ServerMenuAction.ShareFullContent,
        )
        assertEquals(expected, serverMenuActions(isComplexProfile = false, includeManagementActions = false))
    }

    @Test
    fun regularMoreMenuContainsEveryActionInDisplayOrder() {
        assertEquals(
            ServerMenuAction.entries,
            serverMenuActions(isComplexProfile = false, includeManagementActions = true, supportsFragmentation = true),
        )
    }

    @Test
    fun complexShareMenuContainsOnlyFullContent() {
        assertEquals(
            listOf(ServerMenuAction.ShareFullContent),
            serverMenuActions(isComplexProfile = true, includeManagementActions = false),
        )
    }

    @Test
    fun complexMoreMenuRetainsManagementActions() {
        val expected = listOf(
            ServerMenuAction.ShareFullContent,
            ServerMenuAction.Edit,
            ServerMenuAction.Delete,
        )
        assertEquals(expected, serverMenuActions(isComplexProfile = true, includeManagementActions = true))
    }

    @Test fun fragmentTrialsRequireEligibleManagementMenuAndMapToDistinctPresets() {
        val actions = ServerMenuAction.entries.filter { fragmentPreset(it) != null }
        assertEquals(com.v2ray.ang.core.FragmentPreset.entries, actions.map { fragmentPreset(it) })
        assertEquals(listOf(false, false, false), actions.map { it in serverMenuActions(false, false, true) })
        assertEquals(listOf(false, false, false), actions.map { it in serverMenuActions(false, true, false) })
        assertEquals(listOf(false, false, false), actions.map { it in serverMenuActions(true, true, true) })
        actions.forEach { assertEquals(it.labelRes, fragmentPresetLabel(checkNotNull(fragmentPreset(it)))) }
    }
}
