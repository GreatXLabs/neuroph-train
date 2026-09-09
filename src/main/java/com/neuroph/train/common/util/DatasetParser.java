package com.neuroph.train.common.util;

import com.neuroph.train.common.model.ColumnConfig;
import com.neuroph.train.common.model.ColumnRole;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.NormalizationType;
import com.neuroph.train.common.model.TaskType;
import org.neuroph.core.data.DataSet;
import org.neuroph.core.data.DataSetRow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Utilidad para parsear CSV, normalizar datos y dividirlos en conjuntos de entrenamiento (70%) y prueba (30%).
 */
public final class DatasetParser {

    public static class SplitResult {
        private final DataSet trainSet;
        private final List<DataSample> testSamples;

        public SplitResult(DataSet trainSet, List<DataSample> testSamples) {
            this.trainSet = trainSet;
            this.testSamples = testSamples;
        }

        public DataSet getTrainSet() {
            return trainSet;
        }

        public List<DataSample> getTestSamples() {
            return testSamples;
        }
    }

    public static class DataSample {
        private final double[] inputs;
        private final double[] desiredOutputs;
        private final int classIndex; // Para clasificación

        public DataSample(double[] inputs, double[] desiredOutputs, int classIndex) {
            this.inputs = inputs;
            this.desiredOutputs = desiredOutputs;
            this.classIndex = classIndex;
        }

        public double[] getInputs() {
            return inputs;
        }

        public double[] getDesiredOutputs() {
            return desiredOutputs;
        }

        public int getClassIndex() {
            return classIndex;
        }
    }

    public static class CsvInspectionResult {
        private final String delimiter;
        private final int columnCount;
        private final int rowCount;
        private final List<String> headers;
        private final List<ColumnConfig> columnConfigs;
        private final List<String[]> previewRows;

        public CsvInspectionResult(String delimiter, int columnCount, int rowCount,
                                   List<String> headers, List<ColumnConfig> columnConfigs,
                                   List<String[]> previewRows) {
            this.delimiter = delimiter;
            this.columnCount = columnCount;
            this.rowCount = rowCount;
            this.headers = headers;
            this.columnConfigs = columnConfigs;
            this.previewRows = previewRows;
        }

        public String getDelimiter() {
            return delimiter;
        }

        public int getColumnCount() {
            return columnCount;
        }

        public int getRowCount() {
            return rowCount;
        }

        public List<String> getHeaders() {
            return headers;
        }

        public List<ColumnConfig> getColumnConfigs() {
            return columnConfigs;
        }

        public List<String[]> getPreviewRows() {
            return previewRows;
        }
    }

    private DatasetParser() {
    }

    /**
     * Inspecciona el contenido CSV detectando delimitador, cantidad de columnas, nombres y pre-llenando roles.
     */
    public static CsvInspectionResult inspectCsv(String csvContent, boolean hasHeader) throws IOException {
        if (csvContent == null || csvContent.trim().isEmpty()) {
            throw new IllegalArgumentException("El contenido CSV no puede estar vacío");
        }

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    lines.add(line);
                }
            }
        }

        if (lines.isEmpty()) {
            throw new IllegalArgumentException("El CSV no contiene filas de datos");
        }

        String firstLine = lines.get(0);
        String delimiter = detectDelimiter(firstLine);
        String[] firstTokens = firstLine.split(delimiter);
        int colCount = firstTokens.length;

        List<String> headers = new ArrayList<>();
        if (hasHeader) {
            for (String t : firstTokens) {
                headers.add(t.trim());
            }
        } else {
            for (int i = 0; i < colCount; i++) {
                headers.add("Columna " + i);
            }
        }

        // Pre-llenar configuraciones por defecto: 0..N-2 INPUT (MIN_MAX_0_1), N-1 OUTPUT (NONE)
        List<com.neuroph.train.common.model.ColumnConfig> configs = new ArrayList<>();
        for (int i = 0; i < colCount; i++) {
            String colName = headers.get(i);
            if (i == colCount - 1 && colCount > 1) {
                configs.add(new com.neuroph.train.common.model.ColumnConfig(
                        i, colName, com.neuroph.train.common.model.ColumnRole.OUTPUT, NormalizationType.NONE
                ));
            } else {
                configs.add(new com.neuroph.train.common.model.ColumnConfig(
                        i, colName, com.neuroph.train.common.model.ColumnRole.INPUT, NormalizationType.MIN_MAX_0_1
                ));
            }
        }

        int startRow = hasHeader ? 1 : 0;
        int dataRowCount = lines.size() - startRow;

        List<String[]> preview = new ArrayList<>();
        int maxPreview = Math.min(10, dataRowCount);
        for (int i = 0; i < maxPreview; i++) {
            String[] tokens = lines.get(startRow + i).split(delimiter);
            preview.add(tokens);
        }

        return new CsvInspectionResult(delimiter, colCount, dataRowCount, headers, configs, preview);
    }

    /**
     * Parsea el CSV aplicando la partición train/test y normalización estándar (sin data augmentation).
     */
    public static SplitResult parseAndSplit(String csvContent, DatasetMetadata meta, double trainRatio, long seed) throws IOException {
        return parseAndSplit(csvContent, meta, trainRatio, seed, false, 0, 0.0);
    }

    /**
     * Parsea el CSV aplicando partición train/test, normalización granular por columna y Data Augmentation opcional.
     */
    public static SplitResult parseAndSplit(String csvContent, DatasetMetadata meta, double trainRatio, long seed,
                                            boolean enableAugmentation, int augmentationFactor, double augmentationNoise) throws IOException {
        if (csvContent == null || csvContent.trim().isEmpty()) {
            throw new IllegalArgumentException("El contenido CSV no puede estar vacío");
        }

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    lines.add(line);
                }
            }
        }

        if (lines.isEmpty()) {
            throw new IllegalArgumentException("El CSV no contiene filas de datos");
        }

        // Determinar si hay encabezado respetando la configuración de meta
        String firstLine = lines.get(0);
        String delimiter = detectDelimiter(firstLine);
        String[] firstTokens = firstLine.split(delimiter);

        int startRow = 0;
        if (meta != null) {
            startRow = meta.isHasHeader() ? 1 : 0;
        } else {
            try {
                Double.parseDouble(firstTokens[0].trim());
                startRow = 0;
            } catch (NumberFormatException e) {
                startRow = 1;
            }
        }

        List<double[]> rawInputs = new ArrayList<>();
        List<double[]> rawOutputs = new ArrayList<>();
        List<Integer> classIndices = new ArrayList<>();

        List<Integer> inCols = meta.getInputColumns();
        List<Integer> outCols = meta.getOutputColumns();

        for (int i = startRow; i < lines.size(); i++) {
            String[] tokens = lines.get(i).split(delimiter);
            int minRequired = 0;
            for (int col : inCols) if (col >= minRequired) minRequired = col + 1;
            for (int col : outCols) if (col >= minRequired) minRequired = col + 1;

            if (tokens.length < minRequired) {
                continue; // Saltear filas incompletas
            }

            double[] in = new double[inCols.size()];
            for (int j = 0; j < inCols.size(); j++) {
                int colIdx = inCols.get(j);
                in[j] = Double.parseDouble(tokens[colIdx].trim());
            }

            int numClasses = meta.getClassLabels() != null ? meta.getClassLabels().size() : 0;
            double[] out;
            int classIdx = -1;

            if (meta.getTaskType() == TaskType.CLASSIFICATION && numClasses > 2) {
                // One-hot encoding
                out = new double[numClasses];
                String rawVal = tokens[outCols.get(0)].trim();
                classIdx = findClassIndex(rawVal, meta.getClassLabels());
                if (classIdx >= 0 && classIdx < numClasses) {
                    out[classIdx] = 1.0;
                }
            } else {
                out = new double[outCols.size()];
                for (int j = 0; j < outCols.size(); j++) {
                    int colIdx = outCols.get(j);
                    try {
                        out[j] = Double.parseDouble(tokens[colIdx].trim());
                    } catch (NumberFormatException e) {
                        out[j] = 0.0;
                    }
                }
                if (meta.getTaskType() == TaskType.CLASSIFICATION && out.length == 1) {
                    classIdx = (int) Math.round(out[0]);
                }
            }

            rawInputs.add(in);
            rawOutputs.add(out);
            classIndices.add(classIdx);
        }

        // Normalización granular por columna
        normalizeInputsGranular(rawInputs, inCols, meta);

        // Construcción de la lista de muestras
        List<DataSample> allSamples = new ArrayList<>();
        for (int i = 0; i < rawInputs.size(); i++) {
            allSamples.add(new DataSample(rawInputs.get(i), rawOutputs.get(i), classIndices.get(i)));
        }

        DataSet trainSet = new DataSet(meta.getInputCount(), meta.getOutputCount());
        List<DataSample> testSamples = new ArrayList<>();
        Random rand = new Random(seed);

        if (meta.getTaskType() == TaskType.CLASSIFICATION) {
            // Partición estratificada por clase
            java.util.Map<Integer, List<DataSample>> grouped = new java.util.LinkedHashMap<>();
            for (DataSample s : allSamples) {
                grouped.computeIfAbsent(s.getClassIndex(), k -> new ArrayList<>()).add(s);
            }

            for (List<DataSample> classSamples : grouped.values()) {
                Collections.shuffle(classSamples, rand);
                int nTrain = (int) Math.round(classSamples.size() * trainRatio);
                if (nTrain >= classSamples.size() && classSamples.size() > 1) {
                    nTrain = classSamples.size() - 1;
                }
                for (int i = 0; i < classSamples.size(); i++) {
                    DataSample s = classSamples.get(i);
                    if (i < nTrain) {
                        addSampleWithOptionalAugmentation(trainSet, s, enableAugmentation, augmentationFactor, augmentationNoise, rand);
                    } else {
                        testSamples.add(s);
                    }
                }
            }
        } else {
            // Barajar estándar para regresión
            List<DataSample> shuffled = new ArrayList<>(allSamples);
            Collections.shuffle(shuffled, rand);

            int trainCount = (int) Math.round(shuffled.size() * trainRatio);
            if (trainCount >= shuffled.size()) {
                trainCount = shuffled.size() - 1;
            }
            if (trainCount < 1) {
                trainCount = 1;
            }

            for (int i = 0; i < shuffled.size(); i++) {
                DataSample sample = shuffled.get(i);
                if (i < trainCount) {
                    addSampleWithOptionalAugmentation(trainSet, sample, enableAugmentation, augmentationFactor, augmentationNoise, rand);
                } else {
                    testSamples.add(sample);
                }
            }
        }

        return new SplitResult(trainSet, testSamples);
    }

    private static void addSampleWithOptionalAugmentation(DataSet trainSet, DataSample sample,
                                                          boolean enableAugmentation, int factor, double noise,
                                                          Random rand) {
        // 1. Agregar muestra original intacta
        trainSet.add(new DataSetRow(sample.getInputs(), sample.getDesiredOutputs()));

        // 2. Si Data Augmentation está activo, agregar copias sintéticas con perturbación gaussiana
        if (enableAugmentation && factor > 0 && noise > 0.0) {
            int inputDim = sample.getInputs().length;
            for (int k = 0; k < factor; k++) {
                double[] noisy = new double[inputDim];
                for (int d = 0; d < inputDim; d++) {
                    noisy[d] = sample.getInputs()[d] + rand.nextGaussian() * noise;
                }
                trainSet.add(new DataSetRow(noisy, sample.getDesiredOutputs()));
            }
        }
    }

    private static String detectDelimiter(String line) {
        if (line.contains(";")) return ";";
        if (line.contains("\t")) return "\t";
        return ",";
    }

    private static int findClassIndex(String value, List<String> labels) {
        if (labels == null) return -1;
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).equalsIgnoreCase(value)) {
                return i;
            }
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Normaliza las columnas de entrada respetando la configuración granular por columna.
     */
    private static void normalizeInputsGranular(List<double[]> inputs, List<Integer> inCols, DatasetMetadata meta) {
        if (inputs.isEmpty()) {
            return;
        }

        int dims = inputs.get(0).length;

        for (int d = 0; d < dims; d++) {
            int originalCol = (inCols != null && d < inCols.size()) ? inCols.get(d) : d;
            NormalizationType type = (meta != null) ? meta.getNormalizationForColumn(originalCol) : NormalizationType.MIN_MAX_0_1;

            if (type == null || type == NormalizationType.NONE) {
                continue;
            }

            if (type == NormalizationType.MIN_MAX_0_1 || type == NormalizationType.MIN_MAX_MINUS1_1) {
                double min = Double.POSITIVE_INFINITY;
                double max = Double.NEGATIVE_INFINITY;
                for (double[] in : inputs) {
                    if (in[d] < min) min = in[d];
                    if (in[d] > max) max = in[d];
                }
                double range = max - min;
                for (double[] in : inputs) {
                    if (range > 1e-9) {
                        if (type == NormalizationType.MIN_MAX_0_1) {
                            in[d] = (in[d] - min) / range;
                        } else {
                            in[d] = 2.0 * ((in[d] - min) / range) - 1.0;
                        }
                    } else {
                        in[d] = 0.0;
                    }
                }
            } else if (type == NormalizationType.Z_SCORE) {
                double sum = 0.0;
                for (double[] in : inputs) {
                    sum += in[d];
                }
                double mean = sum / inputs.size();
                double varianceSum = 0.0;
                for (double[] in : inputs) {
                    double diff = in[d] - mean;
                    varianceSum += diff * diff;
                }
                double stdDev = Math.sqrt(varianceSum / inputs.size());
                for (double[] in : inputs) {
                    if (stdDev > 1e-9) {
                        in[d] = (in[d] - mean) / stdDev;
                    } else {
                        in[d] = 0.0;
                    }
                }
            }
        }
    }
}
