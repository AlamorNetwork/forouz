package com.v2ray.ang.service

import kotlinx.coroutines.Job

internal enum class LabCommand { START, CANCEL, KEEP, STOP }

// Cancellation marks a Job inactive before its NonCancellable native/network teardown finishes.
internal fun labHasWork(job: Job?): Boolean = job?.isCompleted == false

/** A second start never creates another native controller, process binding or result writer. */
internal fun labCommand(action: String?, hasRequest: Boolean, running: Boolean): LabCommand = when {
    action == "lab.cancel" -> LabCommand.CANCEL
    running -> LabCommand.KEEP
    action == "lab.start" && hasRequest -> LabCommand.START
    else -> LabCommand.STOP
}
