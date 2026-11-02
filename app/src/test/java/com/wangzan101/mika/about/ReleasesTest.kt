package com.wangzan101.mika.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    private val github = """
        {
          "tag_name": "v0.2.0",
          "name": "Mika 0.2.0",
          "html_url": "https://github.com/sxkiss/Mika/releases/tag/v0.2.0",
          "published_at": "2026-11-05T08:30:00Z",
          "prerelease": false,
          "body": "### 新增\r\n- 附件面板\r\n",
          "assets": [
            {"name": "mika-0.2.0-gpl-sources.tar.gz", "browser_download_url": "https://github.com/x/src.tar.gz"},
            {"name": "mika-0.2.0.apk", "browser_download_url": "https://github.com/x/mika-0.2.0.apk", "size": 12345, "digest": "sha256:abc"}
          ]
        }
    """.trimIndent()

    @Test
    fun readsGitHubRelease() {
        val r = Releases.fromGitHub(github)
        assertEquals("0.2.0", r.version)
        assertEquals("Mika 0.2.0", r.name)
        assertEquals("2026-11-05", r.date)
        assertEquals("https://github.com/sxkiss/Mika/releases/tag/v0.2.0", r.pageUrl)
        assertEquals("https://github.com/x/mika-0.2.0.apk", r.apkUrl)
        assertEquals(r.apkUrl, r.downloadUrl)
        assertEquals(12345L, r.apkSize)
        assertEquals("sha256:abc", r.apkDigest)
        assertTrue(r.notes.contains("附件面板"))
    }

    @Test
    fun gitHubReleaseWithoutApkOrBody() {
        val r = Releases.fromGitHub("""{"tag_name":"0.3.0","name":"","body":null,"html_url":"https://github.com/sxkiss/Mika/releases/tag/0.3.0","assets":[]}""")
        assertEquals("0.3.0", r.version)
        assertEquals("0.3.0", r.name)
        assertEquals("", r.notes)
        assertNull(r.apkUrl)
        assertNull(r.apkSize)
        assertNull(r.apkDigest)
        assertNull(r.date)
        assertEquals(r.pageUrl, r.downloadUrl)
    }

    @Test(expected = Exception::class)
    fun gitHubErrorBodyIsRejected() {
        Releases.fromGitHub("""{"message":"API rate limit exceeded","documentation_url":"https://docs.github.com"}""")
    }

    @Test
    fun storedReleaseRoundTrips() {
        val r = Releases.fromGitHub(github)
        assertEquals(r, Releases.fromJson(Releases.toJson(r)))
        val bare = r.copy(date = null, apkUrl = null)
        assertEquals(bare, Releases.fromJson(Releases.toJson(bare)))
    }
}
