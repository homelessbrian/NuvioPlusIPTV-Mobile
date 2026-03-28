package com.nuvio.app.features.watched

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchedRepositoryTest {
    @Test
    fun watchedItemKey_isTypeAware() {
        assertEquals("movie:tt1", watchedItemKey(type = "movie", id = "tt1"))
    }

    @Test
    fun watchedItemKey_trimsValues() {
        assertEquals("series:abc", watchedItemKey(type = " series ", id = " abc "))
    }
}

