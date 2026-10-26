package com.manjul.kbapi.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A UTC clock tests can move forward. */
class MutableClock(@Volatile var now: Instant = TestKeys.NOW) : Clock() {
    fun advance(d: Duration) {
        now = now.plus(d)
    }

    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}
