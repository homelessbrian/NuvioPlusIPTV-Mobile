package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MetaDetailsParserTest {

    @Test
    fun `parse rejects null meta object without json object cast crash`() {
        assertFailsWith<IllegalStateException> {
            MetaDetailsParser.parse("""{"meta":null}""")
        }
    }
}
