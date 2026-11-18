package com.v2ray.ang.core.lab

import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LabProbeFailureTest {
    @Test fun `certificate causes remain distinct from TLS handshakes`() {
        for ((cause, expected) in listOf(CertificateExpiredException("secret") to LabTlsFailure.EXPIRED,
            CertificateNotYetValidException("secret") to LabTlsFailure.NOT_YET_VALID,
            CertPathValidatorException("secret") to LabTlsFailure.CHAIN,
            CertificateException("secret") to LabTlsFailure.CERTIFICATE)) {
            val error = SSLHandshakeException("private endpoint").apply { initCause(cause) }
            val result = labProbeFailure(error)
            assertEquals(LabIssue.TLS, result.issue); assertEquals(expected, result.tls)
            assertFalse(result.toString().contains("secret")); assertFalse(result.toString().contains("private endpoint"))
        }
    }
    @Test fun `peer protocol handshake generic TLS IO and wrapped timeout are classified separately`() {
        for ((error, expected) in listOf(SSLPeerUnverifiedException("fixture") to LabTlsFailure.PEER,
            SSLProtocolException("fixture") to LabTlsFailure.PROTOCOL,
            SSLHandshakeException("fixture") to LabTlsFailure.HANDSHAKE, SSLException("fixture") to LabTlsFailure.OTHER))
            assertEquals(expected, labProbeFailure(error).tls)
        val timeout = labProbeFailure(SSLHandshakeException("fixture").apply { initCause(SocketTimeoutException()) })
        assertEquals(LabIssue.TIMEOUT, timeout.issue); assertNull(timeout.tls)
        val io = labProbeFailure(IOException("private endpoint"))
        assertEquals(LabIssue.IO, io.issue); assertNull(io.tls)
    }
    @Test fun `cause traversal is cycle safe bounded and retains only class names`() {
        val a = IOException("secret-a"); val b = IOException("secret-b")
        a.initCause(b); b.initCause(a)
        assertEquals(listOf("IOException", "IOException"), labProbeFailure(a).types)
        var error = IOException("secret")
        repeat(12) { error = IOException("secret", error) }
        assertEquals(8, labProbeFailure(error).types.size)
        assertFalse(labProbeFailure(error).toString().contains("secret"))
    }
}
