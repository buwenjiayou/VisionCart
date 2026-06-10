package com.visioncart.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NetworkSecurityConfigTest {

    @Test
    fun `main network config only permits self use backend cleartext traffic`() {
        val config = File("src/main/res/xml/network_security_config.xml").readText()

        assertTrue(config.contains("""<base-config cleartextTrafficPermitted="false">"""))
        assertTrue(config.contains("""<domain-config cleartextTrafficPermitted="true">"""))
        assertTrue(config.contains("47.94.4.31"))
        assertFalse(config.contains("10.0.2.2"))
        assertFalse(config.contains("localhost"))
    }

    @Test
    fun `debug network config keeps local development cleartext hosts`() {
        val config = File("src/debug/res/xml/network_security_config.xml").readText()

        assertTrue(config.contains("""<base-config cleartextTrafficPermitted="false">"""))
        assertTrue(config.contains("""<domain-config cleartextTrafficPermitted="true">"""))
        assertTrue(config.contains("10.0.2.2"))
        assertTrue(config.contains("localhost"))
        assertTrue(config.contains("127.0.0.1"))
        assertTrue(config.contains("10.13.11.110"))
    }
}
