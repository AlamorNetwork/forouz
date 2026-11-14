package com.v2ray.ang.service

internal enum class HealthCommand { START, STOP, KEEP, REJECT }
internal fun healthCommand(action: String?, validRequest: Boolean, hasWork: Boolean): HealthCommand = when {
    action == "health.stop" -> HealthCommand.STOP
    hasWork -> HealthCommand.KEEP
    action == "health.start" && validRequest -> HealthCommand.START
    else -> HealthCommand.REJECT
}
