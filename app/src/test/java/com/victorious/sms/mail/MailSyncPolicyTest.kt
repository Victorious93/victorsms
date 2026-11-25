package com.victorious.sms.mail

import org.junit.Assert.*
import org.junit.Test

class MailSyncPolicyTest {
    @Test fun periodIsTheJobSchedulerMinimum() = assertEquals(15 * 60 * 1000L, MailSyncPolicy.PERIOD_MS)
    @Test fun retriesOnlyOnFailure() { assertFalse(MailSyncPolicy.reschedule(0)); assertTrue(MailSyncPolicy.reschedule(1)) }
}
