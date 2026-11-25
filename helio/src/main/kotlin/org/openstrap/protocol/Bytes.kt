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

/** Little-endian helpers; the strap's wire format is little-endian throughout. */

fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
fun ByteArray.u32(at: Int): Long = (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
fun ByteArray.s8(at: Int): Int = this[at].toInt()

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    val clean = filter { !it.isWhitespace() && it != ':' }.removePrefix("0x")
    require(clean.length % 2 == 0) { "Odd hex length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/** Small growable buffer for building little-endian payloads. */
class ByteWriter {
    private val out = java.io.ByteArrayOutputStream()
    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v); u8(v ushr 8) }
    fun u32(v: Long) = apply { u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt()) }
    fun u64(v: Long) = apply { u32(v and 0xFFFFFFFFL); u32(v ushr 32) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Sequential little-endian reader. */
class ByteReader(private val data: ByteArray, var pos: Int = 0) {
    val remaining get() = data.size - pos
    fun u8(): Int = data.u8(pos).also { pos += 1 }
    fun s8(): Int = data.s8(pos).also { pos += 1 }
    fun u16(): Int = data.u16(pos).also { pos += 2 }
    fun u32(): Long = data.u32(pos).also { pos += 4 }
    fun bool(): Boolean = u8() != 0
    fun bytes(n: Int): ByteArray = data.copyOfRange(pos, pos + n).also { pos += n }
    fun rest(): ByteArray = bytes(remaining)
}
