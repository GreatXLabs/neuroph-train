package com.neuroph.train.common.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Metadatos descriptivos de un dataset cargado en el sistema con soporte de configuración granular de columnas.
 */
public class DatasetMetadata {

    private String id;
    private String name;
    private String filename;
    private int numRows;
    private boolean hasHeader = true;

    // Configuración granular por columna
    private List<ColumnConfig> columnConfigs = new ArrayList<>();

    // Listas compatibles de índices de columnas
    private List<Integer> inputColumns = new ArrayList<>();
    private List<Integer> outputColumns = new ArrayList<>();
    private List<String> columnHeaders = new ArrayList<>();

    private TaskType taskType = TaskType.CLASSIFICATION;
    private List<String> classLabels = new ArrayList<>();
    private NormalizationType normalization = NormalizationType.MIN_MAX_0_1; // fallback global
    private long createdAt;
    private String csvContent; // Usado durante el upload o sincronización

    public DatasetMetadata() {
        this.createdAt = System.currentTimeMillis();
    }

    public List<Integer> getInputColumns() {
        if (columnConfigs != null && !columnConfigs.isEmpty()) {
            List<Integer> inputs = new ArrayList<>();
            for (ColumnConfig c : columnConfigs) {
                if (c.getRole() == ColumnRole.INPUT) {
                    inputs.add(c.getIndex());
                }
            }
            return inputs;
        }
        return inputColumns != null ? inputColumns : new ArrayList<>();
    }

    public List<Integer> getOutputColumns() {
        if (columnConfigs != null && !columnConfigs.isEmpty()) {
            List<Integer> outputs = new ArrayList<>();
            for (ColumnConfig c : columnConfigs) {
                if (c.getRole() == ColumnRole.OUTPUT) {
                    outputs.add(c.getIndex());
                }
            }
            return outputs;
        }
        return outputColumns != null ? outputColumns : new ArrayList<>();
    }

    public NormalizationType getNormalizationForColumn(int colIndex) {
        if (columnConfigs != null) {
            for (ColumnConfig c : columnConfigs) {
                if (c.getIndex() == colIndex) {
                    return c.getNormalization() != null ? c.getNormalization() : normalization;
                }
            }
        }
        return normalization != null ? normalization : NormalizationType.MIN_MAX_0_1;
    }

    public int getInputCount() {
        return getInputColumns().size();
    }

    public int getOutputCount() {
        if (taskType == TaskType.CLASSIFICATION && classLabels != null && classLabels.size() > 2) {
            // One-hot encoding para multiclase
            return classLabels.size();
        }
        return getOutputColumns().size() > 0 ? getOutputColumns().size() : 1;
    }

    // Getters y Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public int getNumRows() {
        return numRows;
    }

    public void setNumRows(int numRows) {
        this.numRows = numRows;
    }

    public boolean isHasHeader() {
        return hasHeader;
    }

    public void setHasHeader(boolean hasHeader) {
        this.hasHeader = hasHeader;
    }

    public List<ColumnConfig> getColumnConfigs() {
        return columnConfigs;
    }

    public void setColumnConfigs(List<ColumnConfig> columnConfigs) {
        this.columnConfigs = columnConfigs;
    }

    public void setInputColumns(List<Integer> inputColumns) {
        this.inputColumns = inputColumns;
    }

    public void setOutputColumns(List<Integer> outputColumns) {
        this.outputColumns = outputColumns;
    }

    public List<String> getColumnHeaders() {
        return columnHeaders;
    }

    public void setColumnHeaders(List<String> columnHeaders) {
        this.columnHeaders = columnHeaders;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public void setTaskType(TaskType taskType) {
        this.taskType = taskType;
    }

    public List<String> getClassLabels() {
        return classLabels;
    }

    public void setClassLabels(List<String> classLabels) {
        this.classLabels = classLabels;
    }

    public NormalizationType getNormalization() {
        return normalization;
    }

    public void setNormalization(NormalizationType normalization) {
        this.normalization = normalization;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getCsvContent() {
        return csvContent;
    }

    public void setCsvContent(String csvContent) {
        this.csvContent = csvContent;
    }
}
