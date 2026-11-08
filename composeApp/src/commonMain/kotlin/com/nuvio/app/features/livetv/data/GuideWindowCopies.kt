package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.EpgProgram

/** Short, owned schedules shared by aliases during one guide import's publication. */
internal class GuideWindowCopies(private val fromMs: Long, private val toMs: Long) {
    private val byGuide = HashMap<EpgAssignment, List<EpgProgram>>()

    // Every alias of the same selected source/channel has the same cleaned full schedule.
    // Copy once; neither the input buffers nor these published lists are modified hereafter.
    fun forGuide(sourceId: String, xmltvId: String, programs: List<EpgProgram>): List<EpgProgram> =
        byGuide.getOrPut(EpgAssignment(sourceId, xmltvId)) {
            programs.filter { it.stopMs > fromMs && it.startMs < toMs }.map {
                EpgProgram(it.startMs, it.stopMs, it.title, episode = it.episode, icon = it.icon)
            }
        }
}
