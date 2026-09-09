package com.neuroph.train;

import com.neuroph.train.common.protocol.Message;
import com.neuroph.train.common.protocol.MessageType;
import com.neuroph.train.common.protocol.WebSocketFraming;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketFramingTest {

    @Test
    void testIsHttpRequestDetection() throws Exception {
        byte[] httpBytes = "GET /ws HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        BufferedInputStream httpIn = new BufferedInputStream(new ByteArrayInputStream(httpBytes));
        assertTrue(WebSocketFraming.isHttpRequest(httpIn));

        // Binary length prefix (e.g. integer 42 = 0x0000002A)
        byte[] binaryBytes = new byte[]{0, 0, 0, 42, 1, 2, 3};
        BufferedInputStream binaryIn = new BufferedInputStream(new ByteArrayInputStream(binaryBytes));
        assertFalse(WebSocketFraming.isHttpRequest(binaryIn));
    }

    @Test
    void testWebSocketHandshake() throws Exception {
        // RFC 6455 official test vector:
        // Key: "dGhlIHNhbXBsZSBub25jZQ==" -> Sec-WebSocket-Accept: "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSocketFraming.sendWebSocketHandshakeResponse(out, "dGhlIHNhbXBsZSBub25jZQ==");
        String response = out.toString(StandardCharsets.US_ASCII);

        assertTrue(response.contains("HTTP/1.1 101 Switching Protocols"));
        assertTrue(response.contains("Upgrade: websocket"));
        assertTrue(response.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
    }

    @Test
    void testWriteAndReadWebSocketFrame() throws Exception {
        Message msg = Message.of(MessageType.HEARTBEAT, "PING_TEST_PAYLOAD");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSocketFraming.writeWebSocketMessage(out, msg);

        byte[] frameBytes = out.toByteArray();
        // Byte 0 must be 0x81 (FIN=1, Text Opcode=1)
        assertEquals((byte) 0x81, frameBytes[0]);

        // Simular cliente leyendo frame del servidor (sin máscara)
        ByteArrayInputStream in = new ByteArrayInputStream(frameBytes);
        Message readMsg = WebSocketFraming.readWebSocketMessage(in, null);

        assertNotNull(readMsg);
        assertEquals(MessageType.HEARTBEAT, readMsg.getType());
        assertEquals("PING_TEST_PAYLOAD", readMsg.getPayload());
    }

    @Test
    void testReadMaskedWebSocketFrameFromClient() throws Exception {
        // Simular frame enviado por un cliente con máscara
        Message msg = Message.of(MessageType.WORKER_REGISTER, "{\"slots\":4}");
        String json = com.neuroph.train.common.protocol.JsonUtil.toJson(msg);
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
        clientOut.write(0x81); // FIN + Text

        byte[] maskKey = new byte[]{(byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78};
        if (payload.length < 126) {
            clientOut.write(0x80 | payload.length); // MASK bit = 1
        } else {
            clientOut.write(0x80 | 126);
            clientOut.write((payload.length >> 8) & 0xFF);
            clientOut.write(payload.length & 0xFF);
        }
        clientOut.write(maskKey);

        byte[] maskedPayload = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            maskedPayload[i] = (byte) (payload[i] ^ maskKey[i % 4]);
        }
        clientOut.write(maskedPayload);

        ByteArrayInputStream in = new ByteArrayInputStream(clientOut.toByteArray());
        Message readMsg = WebSocketFraming.readWebSocketMessage(in, null);

        assertNotNull(readMsg);
        assertEquals(MessageType.WORKER_REGISTER, readMsg.getType());
        assertTrue(readMsg.getPayload().contains("\"slots\":4"));
    }
}
