package com.neuroph.train.client.worker;

/**
 * Micro-benchmark de CPU ejecutado localmente para evaluar la velocidad de cálculo
 * matricial y en punto flotante del procesador (FLOPS relativos).
 */
public final class BenchmarkUtil {

    private BenchmarkUtil() {
    }

    /**
     * Ejecuta una rutina de multiplicación matricial durante ~40-60 ms y retorna un score
     * de rendimiento normalizado (MFLOPS aproximados).
     */
    public static double runBenchmark() {
        int size = 120;
        double[][] a = new double[size][size];
        double[][] b = new double[size][size];

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                a[i][j] = (i + j + 1) * 0.005;
                b[i][j] = (i - j + 1) * 0.005;
            }
        }

        long start = System.nanoTime();
        double checksum = 0.0;
        int iterations = 0;

        // Medir durante un lapso de 45 ms para no demorar la inicialización
        while (System.nanoTime() - start < 45_000_000L) {
            for (int i = 0; i < size; i++) {
                for (int k = 0; k < size; k++) {
                    double aik = a[i][k];
                    for (int j = 0; j < size; j++) {
                        checksum += aik * b[k][j];
                    }
                }
            }
            iterations++;
        }

        long elapsedNanos = System.nanoTime() - start;
        double elapsedSec = Math.max(0.001, elapsedNanos / 1_000_000_000.0);

        // Operaciones por iteración = 2 * size^3 operaciones de punto flotante (FMA)
        double totalFlops = ((double) iterations) * (2.0 * size * size * size);
        double mflops = (totalFlops / elapsedSec) / 1_000_000.0;

        // Evitar que el optimizador JIT elimine el loop
        if (checksum == Double.MAX_VALUE) {
            System.out.print("");
        }

        // Retornar un score redondeado a 1 decimal (típicamente entre 500 y 15000)
        return Math.max(100.0, Math.round(mflops * 10.0) / 10.0);
    }
}
