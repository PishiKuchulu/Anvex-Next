package com.anvex.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M0 regression tests (H-1, M-2): Pi-path safety-policy gaps.
 * Bare/recursive reads of the project tree can sweep up secret files,
 * so they must never assess SAFE (CommandApprovalService allows SAFE
 * actions with no prompt at all).
 */
class SafetyPolicyM0Test {

    @Test
    fun `bare and env globs count as secret references`() {
        assertTrue(SensitivePaths.commandReferencesSensitive("cat *"))
        assertTrue(SensitivePaths.commandReferencesSensitive("cat *.env"))
        assertTrue(SensitivePaths.commandReferencesSensitive("head -n 999 *"))
    }

    @Test
    fun `legit globs and plain reads stay unflagged`() {
        assertFalse(SensitivePaths.commandReferencesSensitive("cat *.md"))
        assertFalse(SensitivePaths.commandReferencesSensitive("cat README.md"))
    }

    @Test
    fun `recursive reads and secret globs require approval`() {
        for (cmd in listOf(
            "cat *",
            "cat *.env",
            "head -n 999 *",
            "grep -r \"BEGIN\" .",
            "grep -R key .",
            "rg secret",
        )) {
            assertTrue(
                "expected REQUIRES_APPROVAL for: $cmd",
                ActionSafetyPolicy.assess(cmd).requiresApproval,
            )
        }
    }

    @Test
    fun `ordinary reads stay safe`() {
        for (cmd in listOf(
            "cat README.md",
            "cat *.md",
            "grep pattern file.txt",
            "ls",
            "npm run build",
        )) {
            assertFalse(
                "expected SAFE for: $cmd",
                ActionSafetyPolicy.assess(cmd).requiresApproval,
            )
        }
    }

    @Test
    fun `grep dereference recursive is a recursive read and requires approval`() {
        // Fix round: --dereference-recursive is the long form of -R
        // (recursive + follow symlinks) — it swept the tree silently.
        assertTrue(
            SensitivePaths.isRecursiveRead("grep", listOf("--dereference-recursive", "key", ".")),
        )
        assertTrue(
            ActionSafetyPolicy.assess("grep --dereference-recursive key .").requiresApproval,
        )
    }

    @Test
    fun `brace expansion alternatives that can match secrets count as secret references`() {
        // Fix round: a shell with brace expansion turns {*.env,*.key}
        // into *.env *.key before globbing; the filter must see that.
        assertTrue(SensitivePaths.globCouldMatchSecret("{*.env,*.key}"))
        assertTrue(SensitivePaths.globCouldMatchSecret("sub/{*.env,*.key}"))
        assertTrue(SensitivePaths.commandReferencesSensitive("cat {*.env,*.key}"))
        assertTrue(ActionSafetyPolicy.assess("cat {*.env,*.key}").requiresApproval)
    }

    @Test
    fun `legit brace tokens are not secret matches`() {
        // No glob metacharacters inside: a JSON literal and find's {}
        // placeholder are ordinary, working syntax — not secrets.
        assertFalse(SensitivePaths.globCouldMatchSecret("""{"a":1}"""))
        assertFalse(SensitivePaths.globCouldMatchSecret("{}"))
        // Brace alternatives that cannot match a secret stay allowed.
        assertFalse(SensitivePaths.globCouldMatchSecret("{*.md,*.txt}"))
        assertFalse(SensitivePaths.commandReferencesSensitive("cat {*.md,*.txt}"))
    }

    @Test
    fun `chmod plus x requires approval but numeric modes stay safe`() {
        assertTrue(ActionSafetyPolicy.assess("chmod +x run.sh").requiresApproval)
        assertFalse(ActionSafetyPolicy.assess("chmod 755 run.sh").requiresApproval)
    }
}
