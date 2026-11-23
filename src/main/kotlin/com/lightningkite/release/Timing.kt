package com.lightningkite.release

import java.time.Duration
import java.time.Instant

private val processStart: Instant = ProcessHandle.current().info().startInstant().get()
private var lastLap: Instant = processStart
private val timing = System.getenv("LK_TIMING") == "1"

/** With `LK_TIMING=1`, prints [label] to stderr with the time since the previous lap and since the process started. */
fun lap(label: String) {
    if (!timing) return
    val now = Instant.now()
    System.err.println("⏱ %6d ms  (at %6d ms)  %s".format(Duration.between(lastLap, now).toMillis(), Duration.between(processStart, now).toMillis(), label))
    lastLap = now
}
