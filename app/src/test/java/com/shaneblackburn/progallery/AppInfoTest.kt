package com.shaneblackburn.progallery

import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {
    @Test fun authorCreditIsExact() {
        // Capital "R" in BlackbuRn is intentional.
        assertEquals("Created by Shane BlackbuRn", AppInfo.AUTHOR_CREDIT)
    }
}
