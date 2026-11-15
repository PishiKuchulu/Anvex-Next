package com.anvex.core.android.repo

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R-1 persistence-compat lock for the AgentEngine -> EngineChoice rename.
 *
 * The settings selector enum is persisted in exactly two places, both by
 * ENTRY NAME, never by class name:
 *  - DataStore key "agent_engine" stores `v.name` (see SettingsRepository),
 *    read back via `EngineChoice.valueOf(stored)`.
 *  - AppSettings backups are kotlinx JSON; a plain enum property encodes
 *    as its entry name under the (unchanged) `agentEngine` property key.
 * So payloads written BEFORE the rename must decode identically after it.
 * These tests pin that contract.
 */
class EngineChoicePersistenceCompatTest {

    @Test
    fun storedDataStoreStringsDecodeToTheSameEntries() {
        assertEquals(EngineChoice.NATIVE, EngineChoice.valueOf("NATIVE"))
        assertEquals(EngineChoice.PI, EngineChoice.valueOf("PI"))
    }

    @Test
    fun entryNamesWrittenToDataStoreAreUnchanged() {
        // setAgentEngine writes v.name under Keys.AGENT_ENGINE ("agent_engine").
        assertEquals("NATIVE", EngineChoice.NATIVE.name)
        assertEquals("PI", EngineChoice.PI.name)
        assertEquals(2, EngineChoice.entries.size)
    }

    @Test
    fun preRenameBackupPayloadStillDecodes() {
        // A backup produced before the rename carries the entry name only.
        // Decoded through the exact Json configuration restoreFromBackup uses.
        val json = Json { ignoreUnknownKeys = true }
        val pi = json.decodeFromString<AppSettings>("""{"agentEngine":"PI"}""")
        assertEquals(EngineChoice.PI, pi.agentEngine)
        val native = json.decodeFromString<AppSettings>("""{"agentEngine":"NATIVE"}""")
        assertEquals(EngineChoice.NATIVE, native.agentEngine)
        // And a fresh encode still writes the entry name, not a class name.
        val encoded = json.encodeToString(AppSettings.serializer(), AppSettings(agentEngine = EngineChoice.PI))
        assertEquals(true, encoded.contains("\"agentEngine\":\"PI\""))
    }
}
