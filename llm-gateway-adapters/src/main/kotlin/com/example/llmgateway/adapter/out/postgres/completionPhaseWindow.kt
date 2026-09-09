package com.example.llmgateway.adapter.out.postgres

import java.time.Duration

/** The request reserve covers attempt settlement and execution recording, not two fresh windows. */
internal fun completionPhaseWindow(total: Duration, connectionWait: Duration): Duration {
    require(!total.isNegative && !total.isZero)
    val phase = total.dividedBy(2)
    require(phase > connectionWait.plusSeconds(3)) { "Each completion phase must cover pool acquisition, SQL and cleanup" }
    return phase
}
