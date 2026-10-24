package com.smali_generator.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class MapsTokenProtocolTest {
    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static byte[] unhex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test public void requestBytes() {
        byte[] r = MapsTokenProtocol.request("KEY", "C56ABF7C6890113ED6654E381E2A1797DA3E7F6A", "com.example", 1761400000000L);
        assertEquals("0a430a034b45591228433536414246374336383930313133454436363534453338314532413137393744413345374636411a0b636f6d2e6578616d706c6520809cfcdca133", hex(r));
    }

    @Test public void tokenDecoding() {
        assertEquals("ABdcTOKEN", MapsTokenProtocol.token(unhex("0a0941426463544f4b454e")));
        assertEquals("ABdcTOKEN", MapsTokenProtocol.token(unhex("10070a0941426463544f4b454e")));
        assertNull(MapsTokenProtocol.token(new byte[0]));
        assertNull(MapsTokenProtocol.token(unhex("0a00")));
        assertNull(MapsTokenProtocol.token(unhex("0a09414243")));
        assertNull(MapsTokenProtocol.token("<html>".getBytes(StandardCharsets.UTF_8)));
        assertNull(MapsTokenProtocol.token(null));
    }

    @Test public void clientHeaders() {
        assertEquals("kAID8gICCgA=", MapsTokenProtocol.clientHeader(3));
        assertEquals("kAIA8gICCgA=", MapsTokenProtocol.clientHeader(0));
    }

    @Test public void requestChecks() {
        assertEquals(-1, MapsTokenProtocol.check("com.a", "com.a", "K"));
        assertEquals(1, MapsTokenProtocol.check("com.b", "com.a", "K"));
        assertEquals(1, MapsTokenProtocol.check(null, "com.a", "K"));
        assertEquals(3, MapsTokenProtocol.check("com.a", "com.a", null));
        assertEquals(3, MapsTokenProtocol.check("com.a", "com.a", ""));
    }
}
