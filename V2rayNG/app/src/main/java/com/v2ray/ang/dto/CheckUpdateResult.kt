package com.v2ray.ang.dto

enum class UpdateStatus { AVAILABLE, UP_TO_DATE, NO_RELEASE, NO_COMPATIBLE_APK, INVALID_METADATA }

data class CheckUpdateResult(
    val hasUpdate: Boolean,
    val latestVersion: String? = null,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
    val error: String? = null,
    val isPreRelease: Boolean = false,
    val status: UpdateStatus = if (hasUpdate) UpdateStatus.AVAILABLE else UpdateStatus.UP_TO_DATE
)
