package com.nuvio.app.features.livetv.multiview

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The live player's Multiview button: opens Multiview with this channel in the first pane. It
 * carries a stable channel identity, never a stream request.
 */
internal val LocalOpenMultiview = staticCompositionLocalOf<((String?) -> Unit)?> { null }
