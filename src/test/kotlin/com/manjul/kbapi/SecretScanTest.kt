package com.manjul.kbapi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gitleaks-style scan over the repo tree. Fails on vendor keys and PEM private keys.
 * Test-only generated keys may live under src/test/resources/testkeys/ (none are committed today:
 * TestKeys generates them in memory).
 */
class SecretScanTest {

    private val patterns = mapOf(
        "anthropic key" to Regex("sk-ant-[A-Za-z0-9_\\-]{16,}"),
        "google api key" to Regex("AIza[0-9A-Za-z_\\-]{35}"),
        "pem private key" to Regex("-----BEGIN ([A-Z0-9]+ )*PRIVATE KEY-----"),
    )

    private val skippedDirs = setOf(".git", "build", ".gradle", ".kotlin", ".idea")
    private val allowedPrefix = "src/test/resources/testkeys/"

    private fun scan(root: File): List<String> {
        val findings = mutableListOf<String>()
        root.walkTopDown()
            .onEnter { dir -> dir == root || dir.name !in skippedDirs }
            .filter { it.isFile && it.length() < 5_000_000 }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (rel.startsWith(allowedPrefix)) return@forEach
                val text = file.readText(Charsets.ISO_8859_1)
                for ((name, regex) in patterns) {
                    if (regex.containsMatchIn(text)) findings += "$rel: $name"
                }
            }
        return findings
    }

    @Test
    fun `repo tree has no secrets`() {
        val root = File(System.getProperty("user.dir")).absoluteFile
        assertTrue(File(root, "build.gradle.kts").exists(), "scan must run from the backend project root, got $root")
        val findings = scan(root)
        assertTrue(findings.isEmpty(), "secret-like strings found:\n" + findings.joinToString("\n"))
    }

    @Test
    fun `scanner catches synthetic secrets and honours the allow-list`() {
        val tmp = kotlin.io.path.createTempDirectory("secretscan").toFile()
        try {
            // Assembled at runtime so this source file itself never matches.
            val fakeAnthropic = "sk-" + "ant-" + "api03-" + "x".repeat(40)
            val fakeGoogle = "AI" + "za" + "B".repeat(35)
            val fakePem = "-----BEGIN " + "RSA PRIVATE" + " KEY-----\nMIIB\n"
            File(tmp, "a.txt").writeText(fakeAnthropic)
            File(tmp, "b.properties").writeText("key=$fakeGoogle")
            File(tmp, "c.pem").writeText(fakePem)
            File(tmp, "build").mkdirs()
            File(tmp, "build/ignored.txt").writeText(fakeAnthropic)
            File(tmp, "src/test/resources/testkeys").mkdirs()
            File(tmp, "src/test/resources/testkeys/test-only.pem").writeText(fakePem)

            val findings = scan(tmp).sorted()
            assertEquals(listOf("a.txt: anthropic key", "b.properties: google api key", "c.pem: pem private key"), findings)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
