package dev.leonardo.octether.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private val repoReleaseUrl = UpdatePolicy.RELEASE_URL_PREFIX + "v1.7.0"

    @Test
    fun `manifest transforms only a valid repository release`() {
        val release = UpdatePolicy.manifestToRelease(
            UpdateManifestDto(
                versionName = "1.7.0",
                versionCode = 23,
                releaseUrl = repoReleaseUrl,
            ),
        )

        assertEquals(AvailableUpdate("1.7.0", 23, repoReleaseUrl), release)
        assertNull(
            UpdatePolicy.manifestToRelease(
                UpdateManifestDto("1.7.0", 23, "https://github.com/other/repo/releases/tag/v1.7.0"),
            ),
        )
    }

    @Test
    fun `github fallback requires exact tag and release URL`() {
        assertEquals(
            AvailableUpdate("1.7.0", null, repoReleaseUrl),
            UpdatePolicy.githubToRelease(
                GitHubReleaseDto("v1.7.0", repoReleaseUrl),
            ),
        )
        assertNull(
            UpdatePolicy.githubToRelease(
                GitHubReleaseDto("v1.7.0", "https://github.com/other/repo/releases/tag/v1.7.0"),
            ),
        )
    }

    @Test
    fun `github fallback rejects drafts and prereleases`() {
        assertNull(
            UpdatePolicy.githubToRelease(
                GitHubReleaseDto("v1.7.0", repoReleaseUrl, draft = true),
            ),
        )
        assertNull(
            UpdatePolicy.githubToRelease(
                GitHubReleaseDto("v1.7.0", repoReleaseUrl, prerelease = true),
            ),
        )
    }

    @Test
    fun `version code takes precedence and github falls back to semver`() {
        assertTrue(
            UpdatePolicy.isNewer(
                AvailableUpdate("1.0.0", 23, repoReleaseUrl), 22, "9.0.0",
            ),
        )
        assertFalse(
            UpdatePolicy.isNewer(
                AvailableUpdate("9.0.0", 22, UpdatePolicy.RELEASE_URL_PREFIX + "9.0.0"), 22, "1.0.0",
            ),
        )
        assertTrue(
            UpdatePolicy.isNewer(
                AvailableUpdate("1.7.0", null, repoReleaseUrl), 22, "1.6.9",
            ),
        )
    }

    @Test
    fun `manifest requires a positive version code`() {
        assertNull(
            UpdatePolicy.manifestToRelease(
                UpdateManifestDto("1.7.0", 0, repoReleaseUrl),
            ),
        )
    }

    @Test
    fun `rich manifest requires exact package URL and checksum`() {
        val release = UpdatePolicy.manifestToRelease(
            UpdateManifestDto(
                versionName = "1.7.0",
                versionCode = 23,
                releaseUrl = repoReleaseUrl,
                packageName = "dev.leonardo.octether",
                apkUrl = "https://github.com/LeoNardo-LB/oc-tether/releases/download/v1.7.0/oc-remote-plus-1.7.0.apk",
                sha256 = "A".repeat(64),
            ),
        )

        assertEquals("a".repeat(64), release?.sha256)
        assertTrue(release?.let(UpdatePolicy::isInstallable) ?: false)
    }

    @Test
    fun `rich manifest accepts any flavor package name`() {
        val apkUrl = "https://github.com/LeoNardo-LB/oc-tether/releases/download/v1.7.0/oc-remote-plus-1.7.0.apk"
        val sha = "a".repeat(64)

        // stable flavor
        assertNotNull(
            UpdatePolicy.manifestToRelease(
                UpdateManifestDto("1.7.0", 23, repoReleaseUrl, "dev.leonardo.octether", apkUrl, sha),
            ),
        )
        // beta flavor
        assertNotNull(
            UpdatePolicy.manifestToRelease(
                UpdateManifestDto("1.7.0", 23, repoReleaseUrl, "dev.leonardo.octether.beta", apkUrl, sha),
            ),
        )
        // dev flavor
        assertNotNull(
            UpdatePolicy.manifestToRelease(
                UpdateManifestDto("1.7.0", 23, repoReleaseUrl, "dev.leonardo.octether.dev", apkUrl, sha),
            ),
        )
    }

    @Test
    fun `manifest rejects partial or malformed rich metadata but accepts legacy`() {
        val legacy = UpdatePolicy.manifestToRelease(
            UpdateManifestDto("1.7.0", 23, repoReleaseUrl),
        )
        assertTrue(legacy != null)
        assertFalse(legacy?.let(UpdatePolicy::isInstallable) ?: true)
        assertNull(
            UpdatePolicy.manifestToRelease(
                UpdateManifestDto(
                    "1.7.0", 23, repoReleaseUrl,
                    packageName = "dev.leonardo.octether",
                ),
            ),
        )
        assertNull(
            UpdatePolicy.manifestToRelease(
                UpdateManifestDto(
                    "1.7.0", 23, repoReleaseUrl,
                    "dev.leonardo.octether",
                    "https://github.com/LeoNardo-LB/oc-tether/releases/download/v1.7.0/oc-remote-plus-1.7.0.apk",
                    "not-a-sha",
                ),
            ),
        )
    }

    @Test
    fun `semver comparison handles newer older and equal`() {
        assertTrue(UpdatePolicy.compareSemVer("1.7.0", "1.6.9") > 0)
        assertTrue(UpdatePolicy.compareSemVer("1.6.9", "1.7.0") < 0)
        assertEquals(0, UpdatePolicy.compareSemVer("1.7.0", "1.7.0"))
        assertTrue(UpdatePolicy.compareSemVer("2.0.0", "1.9.9") > 0)
        assertTrue(UpdatePolicy.compareSemVer("1.0.0", "1.0.1") < 0)
    }

    @Test
    fun `semver comparison ignores prerelease suffix in core comparison`() {
        // parseSemVer strips the prerelease suffix, so 1.8.0 vs 1.7.0-beta.1 compares [1,8,0] vs [1,7,0]
        assertTrue(UpdatePolicy.compareSemVer("1.8.0", "1.7.0-beta.1") > 0)
        // Same core version with different prerelease suffixes are considered equal (known limitation)
        assertEquals(0, UpdatePolicy.compareSemVer("1.7.0", "1.7.0-beta.1"))
    }

    private fun assertNotNull(value: Any?) {
        assertTrue("Expected non-null value", value != null)
    }
}
