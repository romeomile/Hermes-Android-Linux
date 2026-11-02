package com.romirmile.hermes.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEndpointTest {

    @Test
    fun recognisesTheEngineHoweverItIsTyped() {
        listOf(
            "http://127.0.0.1:18642",
            "http://127.0.0.1:18642/",
            "http://127.0.0.1:18642/v1",
            "127.0.0.1:18642",
            "http://localhost:18642/v1/",
            " HTTP://127.0.0.1:18642 "
        ).forEach { assertTrue(it, isLocalEndpoint(it)) }
    }

    @Test
    fun leavesOtherGatewaysAlone() {
        listOf(
            "",
            "http://127.0.0.1:8642",
            "https://hermes.example.com",
            "http://192.168.1.10:18642"
        ).forEach { assertFalse(it, isLocalEndpoint(it)) }
    }
}
