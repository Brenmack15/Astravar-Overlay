package com.yazsras.astravar.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * Keep composition and frame application on Compose's test scheduler.
 *
 * The default unconfined dispatcher can resume a Room emission on its IO
 * thread and re-enter Android layout there. Room keeps its real asynchronous
 * executors; only the test recomposer's continuations are queued here.
 */
internal fun createAstravarComposeRule(): ComposeTestRule =
    createEmptyComposeRule(effectContext = StandardTestDispatcher())
