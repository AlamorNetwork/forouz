package com.v2ray.ang.ui.main

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MainImportInspectionTest {
    private val profile = "vless://fixture@server.invalid:443?security=tls&type=ws#name"
    @Test fun `recognizes profile links without promising a working server`() {
        val inspected = inspectProfileImport(profile)
        assertEquals(1, inspected.profiles)
        assertTrue(inspected.canImport)
        val json = Gson().toJson(inspected)
        assertFalse(json.contains("fixture")); assertFalse(json.contains("server.invalid"))
    }
    @Test fun `subscription advanced and native inputs keep separate counts`() {
        assertEquals(1, inspectProfileImport("https://subscription.invalid/path?token=fixture").subscriptions)
        assertEquals(1, inspectProfileImport("vpn://AAAA_packed").advanced)
        assertEquals(1, inspectProfileImport("[Interface]\nPrivateKey = fixture\n[Peer]\nPublicKey = fixture").advanced)
        assertEquals(1, inspectProfileImport("{\"outbounds\":[]}").advanced)
        assertEquals(1, inspectProfileImport("[]").advanced)
    }
    @Test fun `blank malformed unknown and broken native config have explicit nonimportable results`() {
        assertFalse(inspectProfileImport(" ").canImport)
        assertFalse(inspectProfileImport("not a valid URI").canImport)
        assertEquals(1, inspectProfileImport("unknown://payload").unsupported)
        assertEquals(1, inspectProfileImport("[Interface]\nAddress = 1").malformed)
        assertEquals(1, inspectProfileImport("{invalid JSON").malformed)
    }
    @Test fun `mixed input counts rejected entries and repeated lines without silently removing anything`() {
        val inspected = inspectProfileImport("$profile\n$profile\nunknown://thing\nnot a URI")
        assertEquals(2, inspected.profiles)
        assertEquals(1, inspected.repeated)
        assertEquals(1, inspected.unsupported)
        assertEquals(1, inspected.malformed)
    }
    @Test fun `base64 adapter runs only when needed and failure does not expose raw text`() {
        var calls = 0
        val decoded = inspectProfileImport("encoded") { calls++; profile }
        assertEquals(1, decoded.profiles); assertEquals(1, calls)
        inspectProfileImport(profile) { error("must not decode a native link") }
        assertFalse(inspectProfileImport("encoded") { error("private fixture") }.canImport)
    }
    @Test fun `preview is bounded by input length and line count`() {
        assertTrue(inspectProfileImport("x".repeat(8_000_001)).tooLarge)
        assertTrue(inspectProfileImport((profile + "\n").repeat(20_001)).tooLarge)
        assertFalse(inspectProfileImport((profile + "\n").repeat(20_001)).canImport)
    }
    @Test fun `BOM whitespace and empty lines do not become extra profiles`() {
        assertEquals(1, inspectProfileImport("\uFEFF  $profile\n\n").profiles)
    }
}
