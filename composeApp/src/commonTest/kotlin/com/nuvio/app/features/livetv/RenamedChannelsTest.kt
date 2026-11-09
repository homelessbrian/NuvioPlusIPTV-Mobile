package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.RenamedChannels
import com.nuvio.app.features.livetv.model.LiveChannel
import kotlin.test.Test
import kotlin.test.assertEquals

class RenamedChannelsTest {
    private fun ch(name: String, tvgId: String? = null, url: String = "http://x/$name", tvgName: String? = null, source: String = "pl1") =
        LiveChannel(
            key = "$source|${tvgId ?: ""}|$name", sourceId = source, sourceName = "P", name = name,
            tvgId = tvgId, tvgName = tvgName, logo = null, groupId = "g", group = "g", number = 1, url = url
        )

    @Test
    fun renamedChannelIsFoundByItsStreamLink() {
        val now = listOf(ch("ESPN", url = "http://x/1"), ch("CNN", url = "http://x/2"))
        val links = mapOf("pl1||US: ESPN HD" to "http://x/1", "pl1||CNN" to "http://x/2")
        assertEquals(mapOf("pl1||US: ESPN HD" to "pl1||ESPN"), RenamedChannels.match(listOf("pl1||US: ESPN HD"), now, links, onlyNew = true))
    }

    @Test
    fun renamedChannelIsFoundByGuideIdWithoutAnEarlierList() {
        val now = listOf(ch("ESPN", tvgId = "espn.us"), ch("CNN", tvgId = "cnn.us"))
        assertEquals(mapOf("pl1|espn.us|US: ESPN HD" to "pl1|espn.us|ESPN"),
            RenamedChannels.match(listOf("pl1|espn.us|US: ESPN HD"), now, emptyMap(), onlyNew = false))
    }

    @Test
    fun renamedChannelIsFoundByTvgNameEqualToTheOldName() {
        val now = listOf(ch("ESPN", tvgName = "US: ESPN HD"))
        assertEquals(mapOf("pl1||US: ESPN HD" to "pl1||ESPN"),
            RenamedChannels.match(listOf("pl1||US: ESPN HD"), now, emptyMap(), onlyNew = false))
    }

    @Test
    fun unclearMatchesAndOtherPlaylistsAreLeftAlone() {
        val now = listOf(ch("ESPN", tvgId = "espn.us"), ch("ESPN 2", tvgId = "espn.us"), ch("ESPN", tvgId = "espn.us", source = "pl2"))
        assertEquals(emptyMap<String, String>(),
            RenamedChannels.match(listOf("pl1|espn.us|Old ESPN", "pl3|espn.us|Old"), now, emptyMap(), onlyNew = false))
    }

    @Test
    fun channelsThatWereAlreadyThereAreNotTakenAsTheRenamedOne() {
        val now = listOf(ch("ESPN", tvgId = "espn.us"))
        val links = mapOf("pl1|espn.us|ESPN" to "http://x/ESPN")
        assertEquals(emptyMap<String, String>(),
            RenamedChannels.match(listOf("pl1|espn.us|ESPN SD"), now, links, onlyNew = true))
    }
}
