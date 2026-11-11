package com.nuvio.app.features.livetv.multiview

import androidx.compose.runtime.staticCompositionLocalOf

/** The live player's entry action carries a stable channel identity, never a stream request. */
internal val LocalOpenSportsMultiview = staticCompositionLocalOf<((String?) -> Unit)?> { null }
