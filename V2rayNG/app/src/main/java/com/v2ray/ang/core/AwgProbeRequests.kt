package com.v2ray.ang.core

/** One session/request owns publication. Stop and replacement invalidate even a completed HTTP callback. */
internal class AwgProbeRequests {
    data class Ticket internal constructor(val id: String, val profileGuid: String, internal val generation: Long)
    private var profileGuid: String? = null
    private var generation = 0L
    private var current: Ticket? = null

    @Synchronized fun activate(guid: String) {
        profileGuid = guid
        current = null
        generation++
    }

    @Synchronized fun begin(id: String): Ticket? {
        val guid = profileGuid ?: return null
        if (id.isBlank() || current?.id == id) return null
        return Ticket(id, guid, ++generation).also { current = it }
    }

    @Synchronized fun finish(ticket: Ticket): Boolean {
        if (current != ticket) return false
        current = null
        return true
    }

    @Synchronized fun cancel(id: String): Boolean {
        if (current?.id != id) return false
        current = null
        return true
    }

    @Synchronized fun stop() {
        current = null
        profileGuid = null
        generation++
    }
}

/** An unknown/redacted UID and a physical or another app's network must fail closed. */
internal fun isOwnedAwgVpn(isVpn: Boolean, ownerUid: Int, appUid: Int): Boolean =
    isVpn && appUid >= 0 && ownerUid == appUid
