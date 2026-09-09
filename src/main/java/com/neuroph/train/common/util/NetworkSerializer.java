package com.neuroph.train.common.util;

import org.neuroph.core.NeuralNetwork;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;

/**
 * Utilidad para serializar y deserializar redes neuronales de Neuroph (.nnet) a Base64 y archivos.
 */
public final class NetworkSerializer {

    private NetworkSerializer() {
    }

    /**
     * Serializa una red neuronal de Neuroph a un arreglo de bytes.
     */
    public static byte[] toBytes(NeuralNetwork<?> neuralNetwork) throws IOException {
        if (neuralNetwork == null) {
            throw new IllegalArgumentException("La red neuronal no puede ser nula");
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(neuralNetwork);
            oos.flush();
            return baos.toByteArray();
        }
    }

    /**
     * Serializa una red neuronal a una cadena codificada en Base64.
     */
    public static String toBase64(NeuralNetwork<?> neuralNetwork) throws IOException {
        byte[] bytes = toBytes(neuralNetwork);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Deserializa una red neuronal a partir de un arreglo de bytes.
     */
    public static NeuralNetwork<?> fromBytes(byte[] bytes) throws IOException, ClassNotFoundException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("El arreglo de bytes no puede estar vacío");
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (NeuralNetwork<?>) ois.readObject();
        }
    }

    /**
     * Deserializa una red neuronal a partir de una cadena Base64.
     */
    public static NeuralNetwork<?> fromBase64(String base64) throws IOException, ClassNotFoundException {
        if (base64 == null || base64.trim().isEmpty()) {
            throw new IllegalArgumentException("La cadena Base64 no puede estar vacía");
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        return fromBytes(bytes);
    }

    /**
     * Guarda la red neuronal en un archivo físico (.nnet).
     */
    public static void saveToFile(NeuralNetwork<?> neuralNetwork, File file) throws IOException {
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file);
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(neuralNetwork);
            oos.flush();
        }
    }

    /**
     * Guarda bytes recibidos directamente en un archivo (.nnet).
     */
    public static void saveBase64ToFile(String base64, File file) throws IOException {
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
            fos.flush();
        }
    }

    /**
     * Carga una red neuronal desde un archivo (.nnet).
     */
    public static NeuralNetwork<?> loadFromFile(File file) throws IOException, ClassNotFoundException {
        try (FileInputStream fis = new FileInputStream(file);
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            return (NeuralNetwork<?>) ois.readObject();
        }
    }
}
