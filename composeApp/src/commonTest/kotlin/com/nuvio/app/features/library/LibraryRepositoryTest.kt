package com.nuvio.app.features.library

import com.nuvio.app.features.home.PosterShape
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryRepositoryTest {

    @Test
    fun `display title uses exact type formatting`() {
        assertEquals("Movie", "movie".toLibraryDisplayTitle())
        assertEquals("Anime Series", "anime-series".toLibraryDisplayTitle())
        assertEquals("Tv", "tv".toLibraryDisplayTitle())
        assertEquals("Other", "".toLibraryDisplayTitle())
    }

    @Test
    fun `meta preview mapping preserves exact type and poster shape`() {
        val item = LibraryItem(
            id = "tt1",
            type = "anime-series",
            name = "Title",
            poster = "poster",
            banner = "banner",
            logo = "logo",
            description = "desc",
            releaseInfo = "2024",
            imdbRating = "8.4",
            genres = listOf("Drama"),
            posterShape = PosterShape.Poster,
            savedAtEpochMs = 1L,
        )

        val preview = item.toMetaPreview()

        assertEquals("anime-series", preview.type)
        assertEquals(PosterShape.Poster, preview.posterShape)
        assertEquals("banner", preview.banner)
    }
}
