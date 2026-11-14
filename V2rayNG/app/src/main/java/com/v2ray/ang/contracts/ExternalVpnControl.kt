package com.v2ray.ang.contracts

/** Lifecycle remains owned by the active service in the daemon process, never by the UI. */
interface ExternalVpnControl : ServiceControl {
    val engineRunning: Boolean
    val runningProfileName: String
}
