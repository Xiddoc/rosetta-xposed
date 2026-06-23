package io.github.xiddoc.rosetta.gradle.internal

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitHubTarballSourceTest {
    // ---- a hand-built tar.gz (only the fields the reader uses are set) ----

    private class TarBuilder {
        private val body = ByteArrayOutputStream()

        fun add(
            name: String,
            data: ByteArray = ByteArray(0),
            type: Char = '0',
        ): TarBuilder {
            val header = ByteArray(BLOCK)
            putAscii(header, 0, name) // name[100]
            putAscii(header, 124, size11Octal(data.size)) // size[12]
            header[156] = type.code.toByte() // typeflag
            body.write(header)
            body.write(data)
            body.write(ByteArray((BLOCK - data.size % BLOCK) % BLOCK)) // pad to 512
            return this
        }

        /** A GNU long-name ('L') record naming the next entry. */
        fun addLongName(name: String): TarBuilder = add("././@LongLink", name.encodeToByteArray(), 'L')

        fun gzip(): ByteArray {
            body.write(ByteArray(BLOCK)) // end-of-archive zero block
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(body.toByteArray()) }
            return out.toByteArray()
        }

        private companion object {
            const val BLOCK = 512

            fun putAscii(
                buf: ByteArray,
                offset: Int,
                s: String,
            ) {
                val bytes = s.encodeToByteArray()
                System.arraycopy(bytes, 0, buf, offset, bytes.size)
            }

            fun size11Octal(size: Int): String = size.toString(radix = 8).padStart(11, '0')
        }
    }

    private fun sourceOver(
        tarGz: ByteArray,
        captureUrl: (String) -> Unit = {},
    ) = GitHubTarballSource { url ->
        captureUrl(url)
        tarGz
    }

    private val map100 = "{\"version_code\":100}".encodeToByteArray()
    private val map101 = "{\"version_code\":101}".encodeToByteArray()

    // ---- behaviour --------------------------------------------------------

    @Test
    fun `extracts only the json files directly under maps of the requested app`() {
        val tar =
            TarBuilder()
                .add("rosetta-maps-sha/", type = '5') // a directory entry
                .add("rosetta-maps-sha/maps/com.example.victim/", type = '5')
                .add("rosetta-maps-sha/maps/com.example.victim/100.json", map100)
                .add("rosetta-maps-sha/maps/com.example.victim/101.json", map101)
                .add("rosetta-maps-sha/maps/com.example.victim/100.json.att.json", "att".encodeToByteArray())
                .add("rosetta-maps-sha/maps/com.example.victim/nested/inner.json", "x".encodeToByteArray())
                .add("rosetta-maps-sha/maps/net.daylio/267.json", "other".encodeToByteArray())
                .add("rosetta-maps-sha/README.md", "readme".encodeToByteArray())
                .gzip()

        val result = sourceOver(tar).fetchAppMaps("Xiddoc/rosetta-maps", "com.example.victim", "sha")

        assertEquals("sha", result.ref)
        assertEquals(listOf("100.json", "101.json"), result.files.map { it.fileName })
        assertTrue(
            result.files
                .first { it.fileName == "100.json" }
                .bytes
                .contentEquals(map100),
        )
    }

    @Test
    fun `forms the codeload url from repo and ref`() {
        var seen = ""
        val tar = TarBuilder().add("r/maps/com.x/1.json", "{}".encodeToByteArray()).gzip()
        sourceOver(tar) { seen = it }.fetchAppMaps("Owner/Repo", "com.x", "v1.2.3")
        assertEquals("https://codeload.github.com/Owner/Repo/tar.gz/v1.2.3", seen)
    }

    @Test
    fun `honours a GNU long-name record for the entry path`() {
        val longPath = "rosetta-maps-sha/maps/com.example.victim/100.json"
        val tar =
            TarBuilder()
                .addLongName(longPath)
                .add("./short-truncated-name", map100) // real name comes from the 'L' record
                .gzip()

        val result = sourceOver(tar).fetchAppMaps("Xiddoc/rosetta-maps", "com.example.victim", "sha")

        assertEquals(listOf("100.json"), result.files.map { it.fileName })
    }

    @Test
    fun `an empty archive yields no files`() {
        val result = sourceOver(TarBuilder().gzip()).fetchAppMaps("o/r", "com.x", "ref")
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `a non-gzip payload fails`() {
        assertFailsWith<Exception> {
            sourceOver("not a gzip".encodeToByteArray()).fetchAppMaps("o/r", "com.x", "ref")
        }
    }
}
