package com.v2ray.ang.handler

import com.v2ray.ang.enums.EConfigType

/** Untestable native profiles must not lose valid history or be deleted as failed Xray probes. */
internal fun resetDelayBeforeBatch(type: EConfigType, previous: Long): Boolean =
    type != EConfigType.AMNEZIAWG || previous < 0

internal fun mayRemoveAfterDelayTest(type: EConfigType?, delay: Long): Boolean =
    type != null && type != EConfigType.AMNEZIAWG && delay < 0
