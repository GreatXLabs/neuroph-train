package com.neuroph.train.common.model;

/**
 * Rol asignado a una columna del dataset.
 */
public enum ColumnRole {
    INPUT,   // Entrada / Característica (Feature)
    OUTPUT,  // Salida / Objetivo (Target)
    IGNORE   // Ignorar columna (IDs, timestamps, etc.)
}
