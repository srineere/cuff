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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/** A link whose strap takes every write and never answers. */
private class SilentLink : StrapLink {
    override val payloadMtu = 20
    override suspend fun writeControl(data: ByteArray) = Unit
    override suspend fun writeData(data: ByteArray) = Unit
    override val controlNotifications: Flow<ByteArray> = MutableSharedFlow()
    override val dataNotifications: Flow<ByteArray> = MutableSharedFlow()
}

/**
 * A silent strap ends in a plain [ProtocolTimeoutException], not `withTimeout`'s cancellation, so a caller that
 * rethrows cancellations still reports the failure (audit C1).
 */
class ChannelSessionTimeoutTest {
    @Test
    fun requestTimeoutIsAPlainException() = runTest {
        val session = ChannelSession(SilentLink())
        session.start(backgroundScope)
        val e = runCatching { session.request(0x0001, byteArrayOf(1), timeoutMs = 1_000) }.exceptionOrNull()
        assertTrue("got $e", e is ProtocolTimeoutException)
        assertTrue("got $e", e !is CancellationException)
    }

    @Test
    fun handshakeTimeoutIsAPlainException() = runTest {
        val session = ChannelSession(SilentLink())
        session.start(backgroundScope)
        val e = runCatching { session.handshake(ByteArray(16)) }.exceptionOrNull()
        assertTrue("got $e", e is ProtocolTimeoutException)
    }
}
