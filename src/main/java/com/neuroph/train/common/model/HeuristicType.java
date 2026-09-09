package com.neuroph.train.common.model;

/**
 * Estrategia heurística empleada por el servidor para buscar la mejor arquitectura.
 */
public enum HeuristicType {
    GUIDED,        // Búsqueda Guiada / Hill Climbing adaptativo
    EVOLUTIONARY,  // Algoritmo Genético de Hiperparámetros
    HYBRID         // Fase 1 Evolutiva + Fase 2 Afinamiento Guiado
}
