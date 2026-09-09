package com.neuroph.train.common.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Enmarcado de mensajes TCP basado en longitud (Length-Prefixed Framing).
 * Estructura: [4 bytes Big-Endian int: longitud][N bytes UTF-8: JSON del Message].
 * 
 * Evita problemas de fragmentación de paquetes TCP (TCP streaming/packet splitting).
 */
public final class MessageFraming {

    // Tamaño máximo de mensaje permitido (100 MB para permitir datasets y modelos .nnet)
    public static final int MAX_MESSAGE_SIZE = 100 * 1024 * 1024;

    private MessageFraming() {
    }

    /**
     * Escribe un mensaje en el OutputStream con prefijo de longitud de 4 bytes.
     */
    public static synchronized void writeMessage(OutputStream out, Message message) throws IOException {
        if (out == null || message == null) {
            throw new IllegalArgumentException("OutputStream y Message no pueden ser nulos");
        }

        String json = JsonUtil.toJson(message);
        byte[] payloadBytes = json.getBytes(StandardCharsets.UTF_8);

        if (payloadBytes.length > MAX_MESSAGE_SIZE) {
            throw new IOException("El tamaño del mensaje (" + payloadBytes.length + " bytes) excede el máximo permitido (" + MAX_MESSAGE_SIZE + " bytes)");
        }

        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(payloadBytes.length);
        dos.write(payloadBytes);
        dos.flush();
    }

    /**
     * Lee un mensaje completo desde el InputStream respetando el prefijo de longitud.
     * Retorna null si la conexión se cerró limpiamente (EOF).
     */
    public static Message readMessage(InputStream in) throws IOException {
        if (in == null) {
            throw new IllegalArgumentException("InputStream no puede ser nulo");
        }

        DataInputStream dis = new DataInputStream(in);
        int length;
        try {
            length = dis.readInt();
        } catch (EOFException e) {
            return null; // Conexión cerrada limpiamente por el otro extremo
        }

        if (length < 0 || length > MAX_MESSAGE_SIZE) {
            throw new IOException("Longitud de mensaje inválida recibida: " + length + " bytes");
        }

        byte[] buffer = new byte[length];
        dis.readFully(buffer);

        String json = new String(buffer, StandardCharsets.UTF_8);
        return JsonUtil.fromJson(json, Message.class);
    }
}
