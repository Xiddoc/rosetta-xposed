/*
 * A minimal, dependency-free reader for the (gzip-decompressed) TAR stream a
 * GitHub `codeload` tarball is. Just enough USTAR/GNU to pull small JSON files
 * out of `git archive` output: regular files, the GNU long-name ('L') record,
 * and PAX extended headers ('x'/'g'), which we skip (the short
 * `repo-<ref>/maps/<app>/<vc>.json` paths fit the 100-byte USTAR name field, so
 * no PAX/long-name path override is ever needed for the files we keep).
 *
 * Using the JDK's GZIPInputStream + this hand-rolled TAR avoids adding a
 * compression library to the strict dependency-verification set for ~80 lines.
 */
package io.github.xiddoc.rosetta.gradle.internal

import java.io.InputStream

/** One TAR entry: its full path and (for regular files) its bytes. */
internal class TarEntry(
    val name: String,
    val data: ByteArray,
)

/** Streams [TarEntry] records out of a raw (decompressed) TAR [input]. */
internal class TarReader(
    private val input: InputStream,
) {
    /** Returns the next regular-file entry, or `null` at end of archive. */
    fun next(): TarEntry? {
        // A GNU 'L' record carries the following entry's long name; hold it
        // across the header reads until the regular file it precedes.
        var longName: String? = null
        while (true) {
            val header = readBlock() ?: return null
            if (header.all { it == ZERO_BYTE }) return null
            val size = parseOctal(header, SIZE_OFFSET, SIZE_LEN)
            require(size in 0..MAX_ENTRY_BYTES) { "tar entry size $size out of bounds" }
            val data = readData(size.toInt())
            when (val type = header[TYPE_OFFSET].toInt().toChar()) {
                LONGNAME_TYPE -> longName = data.decodeToString().trimEnd(NUL_CHAR)
                PAX_TYPE, PAX_GLOBAL_TYPE -> Unit // skip extended-header metadata
                else ->
                    if (type == REGULAR_TYPE || type == NUL_CHAR) {
                        return TarEntry(longName ?: readName(header), data)
                    } else {
                        longName = null // directories, links, etc.: drop any pending name
                    }
            }
        }
    }

    private fun readName(header: ByteArray): String {
        val name = cString(header, NAME_OFFSET, NAME_LEN)
        val prefix = cString(header, PREFIX_OFFSET, PREFIX_LEN)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    /** Reads exactly [BLOCK] bytes, or `null` if the stream is cleanly at EOF. */
    private fun readBlock(): ByteArray? {
        val buf = ByteArray(BLOCK)
        var read = 0
        while (read < BLOCK) {
            val n = input.read(buf, read, BLOCK - read)
            if (n < 0) return if (read == 0) null else error("truncated tar header")
            read += n
        }
        return buf
    }

    /** Reads [size] payload bytes and consumes the trailing 512-block padding. */
    private fun readData(size: Int): ByteArray {
        val data = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(data, read, size - read)
            require(n >= 0) { "truncated tar entry" }
            read += n
        }
        var padding = (BLOCK - size % BLOCK) % BLOCK
        while (padding > 0) {
            val skipped = input.read(ByteArray(padding))
            require(skipped >= 0) { "truncated tar padding" }
            padding -= skipped
        }
        return data
    }

    private companion object {
        const val BLOCK = 512
        const val ZERO_BYTE: Byte = 0
        val NUL_CHAR = Char(0)
        const val NAME_OFFSET = 0
        const val NAME_LEN = 100
        const val SIZE_OFFSET = 124
        const val SIZE_LEN = 12
        const val TYPE_OFFSET = 156
        const val PREFIX_OFFSET = 345
        const val PREFIX_LEN = 155
        const val REGULAR_TYPE = '0'
        const val LONGNAME_TYPE = 'L'
        const val PAX_TYPE = 'x'
        const val PAX_GLOBAL_TYPE = 'g'

        // A maps repo holds tiny JSON; cap any single entry so a malformed or
        // hostile archive can't drive an unbounded allocation.
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

        const val OCTAL_BASE = 8

        fun cString(
            buf: ByteArray,
            offset: Int,
            len: Int,
        ): String {
            var end = offset
            val limit = offset + len
            while (end < limit && buf[end] != ZERO_BYTE) end++
            return String(buf, offset, end - offset, Charsets.UTF_8)
        }

        fun parseOctal(
            buf: ByteArray,
            offset: Int,
            len: Int,
        ): Long {
            var value = 0L
            for (i in offset until offset + len) {
                val c = buf[i].toInt().toChar()
                if (c == ' ' || c == NUL_CHAR) continue
                require(c in '0'..'7') { "invalid octal digit in tar header" }
                value = value * OCTAL_BASE + (c - '0')
            }
            return value
        }
    }
}
