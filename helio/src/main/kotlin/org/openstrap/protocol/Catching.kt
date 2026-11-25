// SPDX-License-Identifier: MIT
// Copyright (c) 2026 OpenStrap contributors
//
// Vendored into Cuff from sayed0am/unstrap, protocol module, at commit
// 0d323c9bfb74f5374e49f90013888fb0e57fdc57 (2026-11-23). Unmodified except
// for this header. Upstream is dual MIT / Apache-2.0; taken here under MIT,
// which is the licence the rest of this repository uses. The full texts are
// in third_party/unstrap/. See THIRD_PARTY_NOTICES.md.
//
// Keep this file a faithful copy. Fixes belong upstream, where the hardware
// to test them against is; a local edit is a diff somebody has to carry
// forever.

package org.openstrap.protocol

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspend code, the protocol module's copy of the app's `catching`. `runCatching` turns a
 * cancellation into a failure, so a cancelled session would keep answering. This rethrows a [CancellationException]
 * once the calling coroutine is no longer active. A `withTimeout` that expires inside [block] while the caller is
 * still active comes back as a failure, since it is a timeout, not a cancellation.
 */
// Catching everything is the point: it is runCatching with cancellation kept.
@Suppress("TooGenericExceptionCaught")
internal suspend inline fun <T> catching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        Result.failure(e)
    } catch (e: Throwable) {
        Result.failure(e)
    }
