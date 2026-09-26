package net.tfminecraft.drinkbuilder.api;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import net.tfminecraft.tfmcweb.api.ProvinceSystemGateway;
import net.tfminecraft.tfmcweb.api.ProvinceSystemGateway.Response;

class GatewayClientTest {
    @AfterEach void reset() {
        ProvinceSystemGateway.response = null;
        ProvinceSystemGateway.failure = null;
        ProvinceSystemGateway.request = null;
    }
    @Test void requestPassesArgumentsAndConvertsResponses() {
        ProvinceSystemGateway.response = new Response(true, "body", null, null);
        var result = GatewayClient.request("POST", "/test", "{}");
        assertTrue(result.ok); assertEquals("body", result.body); assertNull(result.error);
        assertArrayEquals(new Object[]{"POST", "/test", "{}"}, ProvinceSystemGateway.request);
        ProvinceSystemGateway.response = new Response(true, null, null, null);
        assertEquals("", GatewayClient.request("GET", "/", null).body);
        ProvinceSystemGateway.response = new Response(false, null, "denied", null);
        result = GatewayClient.request("GET", "/", null);
        assertFalse(result.ok); assertNull(result.body); assertEquals("denied", result.error);
        ProvinceSystemGateway.response = new Response(null, null, null, null);
        assertEquals("request failed", GatewayClient.request("GET", "/", null).error);
        assertEquals("", GatewayClient.Result.success(null).body);
    }
    @Test void binaryRequestsAndDownloadsPreserveBytes() {
        byte[] bytes = {1,2,3};
        ProvinceSystemGateway.response = new Response(true, 42, null, bytes);
        assertEquals("42", GatewayClient.requestBytes("PUT", "/image", bytes, "image/png").body);
        assertArrayEquals(new Object[]{"PUT", "/image", bytes, "image/png"}, ProvinceSystemGateway.request);
        var result = GatewayClient.download("/image");
        assertTrue(result.ok); assertArrayEquals(bytes, result.data); assertNull(result.error);
        assertArrayEquals(new Object[]{"/image"}, ProvinceSystemGateway.request);
        ProvinceSystemGateway.response = new Response(false, null, "failed", null);
        assertEquals("failed", GatewayClient.download("/").error);
        ProvinceSystemGateway.response = new Response(false, null, null, null);
        result = GatewayClient.download("/");
        assertEquals("download failed", result.error); assertNull(result.data); assertFalse(result.ok);
    }
    @Test void malformedAndThrowingGatewayFailsSoftly() {
        ProvinceSystemGateway.response = new Object();
        assertFalse(GatewayClient.request("GET", "/", null).ok);
        assertFalse(GatewayClient.requestBytes("PUT", "/", new byte[0], "x").ok);
        assertFalse(GatewayClient.download("/").ok);
        for (String message : Arrays.asList(null, " ", "offline")) {
            ProvinceSystemGateway.failure = new IllegalStateException(message);
            String expected = "TFMCWeb gateway unavailable: " + (message == null || message.isBlank() ? "IllegalStateException" : message);
            assertEquals(expected, GatewayClient.request("GET", "/", null).error);
            assertEquals(expected, GatewayClient.requestBytes("PUT", "/", null, null).error);
            assertEquals(expected, GatewayClient.download("/").error);
        }
    }
}
