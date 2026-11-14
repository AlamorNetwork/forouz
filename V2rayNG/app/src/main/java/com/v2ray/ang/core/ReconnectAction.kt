package com.v2ray.ang.core

/** An acknowledgement, not UI/native singleton state, decides whether Connect may start. */
internal enum class ReconnectAction {
    ALREADY_HANDLED, NEEDS_PERMISSION, START;

    companion object {
        fun decide(daemonHandled: Boolean, permissionRequired: Boolean): ReconnectAction = when {
            daemonHandled -> ALREADY_HANDLED
            permissionRequired -> NEEDS_PERMISSION
            else -> START
        }
    }
}
