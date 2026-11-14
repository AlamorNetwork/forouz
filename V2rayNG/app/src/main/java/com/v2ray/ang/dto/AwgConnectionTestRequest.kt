package com.v2ray.ang.dto

import java.io.Serializable

/** A batch may test only this already-running profile; it must never start or replace a tunnel. */
data class AwgConnectionTestRequest(val requestId: String, val profileGuid: String) : Serializable {
    fun matches(activeGuid: String?, running: Boolean): Boolean = running && requestId.isNotBlank() &&
        profileGuid.isNotBlank() && profileGuid == activeGuid
}
