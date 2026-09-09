package com.neuroph.train.common.model;

/**
 * Configuración individual por columna: índice, nombre, rol y tipo de normalización.
 */
public class ColumnConfig {

    private int index;
    private String name;
    private ColumnRole role = ColumnRole.INPUT;
    private NormalizationType normalization = NormalizationType.MIN_MAX_0_1;

    public ColumnConfig() {
    }

    public ColumnConfig(int index, String name, ColumnRole role, NormalizationType normalization) {
        this.index = index;
        this.name = name;
        this.role = role != null ? role : ColumnRole.INPUT;
        this.normalization = normalization != null ? normalization : NormalizationType.NONE;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ColumnRole getRole() {
        return role;
    }

    public void setRole(ColumnRole role) {
        this.role = role;
    }

    public NormalizationType getNormalization() {
        return normalization;
    }

    public void setNormalization(NormalizationType normalization) {
        this.normalization = normalization;
    }

    @Override
    public String toString() {
        return "ColumnConfig{" +
                "index=" + index +
                ", name='" + name + '\'' +
                ", role=" + role +
                ", norm=" + normalization +
                '}';
    }
}
