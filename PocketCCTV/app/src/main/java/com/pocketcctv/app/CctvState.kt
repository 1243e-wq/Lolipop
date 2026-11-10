package com.pocketcctv.app

import kotlinx.coroutines.flow.MutableStateFlow

/** Live status shared between the background service and the screens (same process). */
object CctvState {
    val running = MutableStateFlow(false)
    val recording = MutableStateFlow(false)
    val segmentStartMillis = MutableStateFlow(0L)
    val message = MutableStateFlow("")
    val viewers = MutableStateFlow(0)
    val port = MutableStateFlow(MjpegServer.PORT)
    val uploadStatus = MutableStateFlow("")
}
