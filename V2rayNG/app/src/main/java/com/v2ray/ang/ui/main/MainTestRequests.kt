package com.v2ray.ang.ui.main

import java.util.UUID

/** Main-thread request ownership; a late reply must not complete a newer test. */
internal class MainTestRequests {
    data class Bulk(val id: String, val groupId: String)

    private data class Current(val id: String, val guid: String?, val sessionId: String?)
    private var current: Current? = null
    var bulk: Bulk? = null
        private set
    val isCurrentTesting: Boolean get() = current != null
    val isTesting: Boolean get() = current != null || bulk != null

    fun beginCurrent(guid: String? = null, sessionId: String? = null): String =
        UUID.randomUUID().toString().also { current = Current(it, guid, sessionId) }

    fun matchesCurrentTarget(id: String, guid: String?, sessionId: String?): Boolean = current?.let {
        it.id == id && (it.guid == null || it.guid == guid) && (it.sessionId == null || it.sessionId == sessionId)
    } == true

    fun completeCurrent(id: String): Boolean {
        if (id != current?.id) return false
        current = null
        return true
    }

    fun invalidateCurrent() {
        current = null
    }

    fun beginBulk(groupId: String): Bulk = Bulk(UUID.randomUUID().toString(), groupId).also { bulk = it }

    fun completeBulk(id: String): Bulk? {
        val request = bulk?.takeIf { it.id == id } ?: return null
        bulk = null
        return request
    }

    fun cancelBulk() {
        bulk = null
    }
}
