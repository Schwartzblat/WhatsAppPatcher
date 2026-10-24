package com.smali_generator.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MapsTokenClientTest {
    static final class Fake implements Runnable {
        final ServerSocket server;
        final int status;
        final String contentType;
        final byte[] reply;
        volatile String requestLine;
        final List<String> headers = new ArrayList<>();
        volatile byte[] body = new byte[0];
        final Thread thread;

        Fake(int status, String contentType, byte[] reply) throws IOException {
            this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            this.status = status; this.contentType = contentType; this.reply = reply;
            thread = new Thread(this); thread.setDaemon(true); thread.start();
        }

        String url() { return "http://127.0.0.1:" + server.getLocalPort(); }

        static String line(InputStream in) throws IOException {
            StringBuilder s = new StringBuilder();
            int c;
            while ((c = in.read()) != -1 && c != '\n') if (c != '\r') s.append((char) c);
            return s.toString();
        }

        @Override public void run() {
            try (Socket s = server.accept()) {
                s.setSoTimeout(5000);
                InputStream in = s.getInputStream();
                requestLine = line(in);
                int len = 0;
                String h;
                while (!(h = line(in)).isEmpty()) {
                    synchronized (headers) { headers.add(h); }
                    if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
                }
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                for (int i = 0; i < len; i++) b.write(in.read());
                body = b.toByteArray();
                OutputStream out = s.getOutputStream();
                String head = "HTTP/1.1 " + status + " X\r\nContent-Type: " + contentType
                        + "\r\nContent-Length: " + reply.length + "\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.write(reply);
                out.flush();
            } catch (IOException ignored) {
            }
        }

        void finish() throws Exception { thread.join(5000); server.close(); }
    }

    static final String PB = "application/x-protobuf";

    @Test public void sendsWhatPlayServicesSends() throws Exception {
        Fake f = new Fake(200, PB, MapsTokenProtocolTest.unhex("0a0941426463544f4b454e"));
        long now = 1761000000000L;
        MapsTokenClient.fetch(f.url(), "KEY", "com.example", "C56ABF7C6890113ED6654E381E2A1797DA3E7F6A", 3, now);
        f.finish();
        assertEquals("POST " + MapsTokenProtocol.PATH + " HTTP/1.1", f.requestLine);
        synchronized (f.headers) {
            assertTrue(f.headers.toString(), f.headers.contains("Content-Type: " + PB));
            assertTrue(f.headers.toString(), f.headers.contains("X-Goog-Api-Key: " + MapsTokenProtocol.PLAY_SERVICES_API_KEY));
            assertTrue(f.headers.toString(), f.headers.contains("X-Gmm-Client-bin: kAID8gICCgA="));
        }
        assertArrayEquals(MapsTokenProtocol.request("KEY", "C56ABF7C6890113ED6654E381E2A1797DA3E7F6A",
                "com.example", now + MapsTokenProtocol.VALIDITY_MILLIS), f.body);
    }

    MapsTokenClient.Result run(Fake f) throws Exception {
        MapsTokenClient.Result r = MapsTokenClient.fetch(f.url(), "K", "com.a", "AA", 3, 1L);
        f.finish();
        return r;
    }

    @Test public void okReply() throws Exception {
        MapsTokenClient.Result r = run(new Fake(200, PB, MapsTokenProtocolTest.unhex("0a0941426463544f4b454e")));
        assertEquals("ABdcTOKEN", r.token);
        assertEquals(-1, r.errorCode);
    }

    @Test public void refused() throws Exception {
        MapsTokenClient.Result r = run(new Fake(400, PB, new byte[0]));
        assertNull(r.token);
        assertEquals(4, r.errorCode);
        r = run(new Fake(200, PB, new byte[0]));
        assertNull(r.token);
        assertEquals(4, r.errorCode);
    }

    @Test public void transientStatusesAreRetryable() throws Exception {
        assertEquals(5, run(new Fake(408, PB, new byte[0])).errorCode);
        assertEquals(5, run(new Fake(429, PB, new byte[0])).errorCode);
        assertEquals(5, run(new Fake(302, PB, new byte[0])).errorCode);
    }

    @Test public void otherClientErrorsAreRefusals() throws Exception {
        assertEquals(4, run(new Fake(403, PB, new byte[0])).errorCode);
    }

    @Test public void notProtobufMeansUnreachable() throws Exception {
        MapsTokenClient.Result r = run(new Fake(200, "text/html", "<html>".getBytes(StandardCharsets.UTF_8)));
        assertNull(r.token);
        assertEquals(5, r.errorCode);
    }

    @Test public void serverErrorOrNoServer() throws Exception {
        assertEquals(5, run(new Fake(503, PB, new byte[0])).errorCode);
        ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = s.getLocalPort();
        s.close();
        MapsTokenClient.Result r = MapsTokenClient.fetch("http://127.0.0.1:" + port, "K", "com.a", "AA", 3, 1L);
        assertNull(r.token);
        assertEquals(5, r.errorCode);
    }
}
