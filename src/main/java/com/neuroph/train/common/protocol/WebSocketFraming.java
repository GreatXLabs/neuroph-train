package com.neuroph.train.common.protocol;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Soporte de enmarcado y protocolo WebSocket (RFC 6455) e inspección HTTP.
 * Permite que el servidor opere en modo dual (TCP puro o WebSockets sobre TLS/Cloudflare/Traefik).
 */
public final class WebSocketFraming {

    public static final int MAX_FRAME_SIZE = MessageFraming.MAX_MESSAGE_SIZE;
    private static final String WS_MAGIC_KEY = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketFraming() {
    }

    /**
     * Comprueba si la conexión entrante comienza con una petición HTTP (ej: "GET ").
     */
    public static boolean isHttpRequest(BufferedInputStream in) throws IOException {
        if (!in.markSupported()) {
            return false;
        }
        in.mark(4);
        byte[] header = new byte[4];
        int read = 0;
        while (read < 4) {
            int b = in.read();
            if (b == -1) break;
            header[read++] = (byte) b;
        }
        in.reset();

        if (read < 4) {
            return false;
        }
        return header[0] == 'G' && header[1] == 'E' && header[2] == 'T' && header[3] == ' ';
    }

    public static class HttpRequest {
        private final String method;
        private final String path;
        private final Map<String, String> headers;

        public HttpRequest(String method, String path, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.headers = headers;
        }

        public String getMethod() {
            return method;
        }

        public String getPath() {
            return path;
        }

        public String getHeader(String name) {
            return headers.get(name.toLowerCase());
        }

        public boolean isWebSocketUpgrade() {
            String upgrade = getHeader("upgrade");
            return upgrade != null && "websocket".equalsIgnoreCase(upgrade.trim());
        }

        public String getWebSocketKey() {
            return getHeader("sec-websocket-key");
        }
    }

    /**
     * Lee la cabecera HTTP inicial hasta la línea en blanco.
     */
    public static HttpRequest readHttpRequest(InputStream in) throws IOException {
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        String requestLine = readAsciiLine(in, lineBuffer);
        if (requestLine == null || requestLine.isEmpty()) {
            throw new IOException("Petición HTTP vacía");
        }

        String[] parts = requestLine.split("\\s+");
        if (parts.length < 2) {
            throw new IOException("Línea de petición HTTP inválida: " + requestLine);
        }
        String method = parts[0];
        String path = parts[1];

        Map<String, String> headers = new HashMap<>();
        String headerLine;
        while ((headerLine = readAsciiLine(in, lineBuffer)) != null && !headerLine.isEmpty()) {
            int colon = headerLine.indexOf(':');
            if (colon > 0) {
                String name = headerLine.substring(0, colon).trim().toLowerCase();
                String val = headerLine.substring(colon + 1).trim();
                headers.put(name, val);
            }
        }

        return new HttpRequest(method, path, headers);
    }

    private static String readAsciiLine(InputStream in, ByteArrayOutputStream buffer) throws IOException {
        buffer.reset();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
            }
        }
        if (b == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    /**
     * Envía la respuesta 101 Switching Protocols para completar el handshake RFC 6455.
     */
    public static void sendWebSocketHandshakeResponse(OutputStream out, String clientKey) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] sha1 = md.digest((clientKey.trim() + WS_MAGIC_KEY).getBytes(StandardCharsets.UTF_8));
            String acceptKey = Base64.getEncoder().encodeToString(sha1);

            String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + acceptKey + "\r\n" +
                    "\r\n";

            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (Exception e) {
            throw new IOException("Error calculando handshake WebSocket: " + e.getMessage(), e);
        }
    }

    /**
     * Envía una respuesta HTTP estándar (para health checks / endpoints informativos).
     */
    public static void sendHttpResponse(OutputStream out, int statusCode, String statusText,
                                        String contentType, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String response = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.write(bodyBytes);
        out.flush();
    }

    /**
     * Lee un mensaje completo empaquetado en frames WebSocket (RFC 6455).
     * Retorna null si el cliente envió un frame de cierre o se cerró el flujo (EOF).
     */
    public static Message readWebSocketMessage(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream payloadStream = new ByteArrayOutputStream();

        while (true) {
            int b1 = in.read();
            if (b1 == -1) {
                return null;
            }

            boolean fin = (b1 & 0x80) != 0;
            int opcode = b1 & 0x0F;

            // Opcode 8: Connection Close
            if (opcode == 8) {
                return null;
            }

            int b2 = in.read();
            if (b2 == -1) {
                throw new EOFException("Conexión cerrada inesperadamente leyendo frame WebSocket");
            }

            boolean masked = (b2 & 0x80) != 0;
            long length = b2 & 0x7F;

            if (length == 126) {
                int byteA = in.read();
                int byteB = in.read();
                if ((byteA | byteB) < 0) throw new EOFException();
                length = ((byteA << 8) | byteB) & 0xFFFF;
            } else if (length == 127) {
                DataInputStream dis = new DataInputStream(in);
                length = dis.readLong();
            }

            if (length < 0 || length > MAX_FRAME_SIZE) {
                throw new IOException("Tamaño de frame WebSocket no permitido: " + length);
            }

            byte[] mask = null;
            if (masked) {
                mask = in.readNBytes(4);
                if (mask.length < 4) throw new EOFException();
            }

            byte[] framePayload = in.readNBytes((int) length);
            if (framePayload.length < length) {
                throw new EOFException("Frame WebSocket incompleto");
            }

            if (masked && mask != null) {
                for (int i = 0; i < framePayload.length; i++) {
                    framePayload[i] = (byte) (framePayload[i] ^ mask[i % 4]);
                }
            }

            // Opcode 9: Ping -> Responder automáticamente con Pong (Opcode 10)
            if (opcode == 9) {
                if (out != null) {
                    sendPong(out, framePayload);
                }
                continue;
            }

            // Opcode 10: Pong -> Ignorar
            if (opcode == 10) {
                continue;
            }

            payloadStream.write(framePayload);

            if (fin) {
                break;
            }
        }

        String json = payloadStream.toString(StandardCharsets.UTF_8);
        return JsonUtil.fromJson(json, Message.class);
    }

    private static synchronized void sendPong(OutputStream out, byte[] payload) throws IOException {
        out.write(0x8A); // FIN=1, Opcode=10 (Pong)
        if (payload.length < 126) {
            out.write(payload.length);
        } else {
            out.write(126);
            out.write((payload.length >> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        }
        out.write(payload);
        out.flush();
    }

    /**
     * Escribe un mensaje en formato de frame de texto WebSocket (RFC 6455).
     */
    public static synchronized void writeWebSocketMessage(OutputStream out, Message message) throws IOException {
        if (out == null || message == null) {
            throw new IllegalArgumentException("OutputStream y Message no pueden ser nulos");
        }

        String json = JsonUtil.toJson(message);
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);

        if (payload.length > MAX_FRAME_SIZE) {
            throw new IOException("Mensaje excede tamaño máximo permitido (" + payload.length + " > " + MAX_FRAME_SIZE + ")");
        }

        // Byte 0: FIN=1, Opcode=1 (Text)
        out.write(0x81);

        // Byte 1..: Longitud sin máscara (Servidor -> Cliente)
        if (payload.length < 126) {
            out.write(payload.length);
        } else if (payload.length <= 65535) {
            out.write(126);
            out.write((payload.length >> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        } else {
            out.write(127);
            DataOutputStream dos = new DataOutputStream(out);
            dos.writeLong(payload.length);
        }

        out.write(payload);
        out.flush();
    }
}
