package com.neuroph.train.common.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadatos descriptivos de un dataset cargado en el sistema.
 */
public class DatasetMetadata {

    private String id;
    private String name;
    private String filename;
    private int numRows;
    private List<Integer> inputColumns = new ArrayList<>();
    private List<Integer> outputColumns = new ArrayList<>();
    private List<String> columnHeaders = new ArrayList<>();
    private TaskType taskType = TaskType.CLASSIFICATION;
    private List<String> classLabels = new ArrayList<>();
    private NormalizationType normalization = NormalizationType.MIN_MAX_0_1;
    private long createdAt;
    private String csvContent; // Usado durante el upload o sincronización

    public DatasetMetadata() {
        this.createdAt = System.currentTimeMillis();
    }

    public int getInputCount() {
        return inputColumns != null ? inputColumns.size() : 0;
    }

    public int getOutputCount() {
        if (taskType == TaskType.CLASSIFICATION && classLabels != null && classLabels.size() > 2) {
            // One-hot encoding para multiclase
            return classLabels.size();
        }
        return outputColumns != null ? outputColumns.size() : 1;
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

    public List<Integer> getInputColumns() {
        return inputColumns;
    }

    public void setInputColumns(List<Integer> inputColumns) {
        this.inputColumns = inputColumns;
    }

    public List<Integer> getOutputColumns() {
        return outputColumns;
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
