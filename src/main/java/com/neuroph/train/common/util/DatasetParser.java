package com.neuroph.train.common.util;

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

    private DatasetParser() {
    }

    /**
     * Parsea el CSV aplicando la partición train/test y normalización.
     */
    public static SplitResult parseAndSplit(String csvContent, DatasetMetadata meta, double trainRatio, long seed) throws IOException {
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

        // Detectar si la primera fila es encabezado
        int startRow = 0;
        String firstLine = lines.get(0);
        String delimiter = detectDelimiter(firstLine);
        String[] firstTokens = firstLine.split(delimiter);

        boolean hasHeader = false;
        try {
            Double.parseDouble(firstTokens[0].trim());
        } catch (NumberFormatException e) {
            hasHeader = true;
            startRow = 1;
        }

        List<double[]> rawInputs = new ArrayList<>();
        List<double[]> rawOutputs = new ArrayList<>();
        List<Integer> classIndices = new ArrayList<>();

        List<Integer> inCols = meta.getInputColumns();
        List<Integer> outCols = meta.getOutputColumns();

        for (int i = startRow; i < lines.size(); i++) {
            String[] tokens = lines.get(i).split(delimiter);
            if (tokens.length < inCols.size() + outCols.size()) {
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

        // Normalización de entradas si corresponde
        normalizeInputs(rawInputs, meta.getNormalization());

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
                        trainSet.add(new DataSetRow(s.getInputs(), s.getDesiredOutputs()));
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
                    trainSet.add(new DataSetRow(sample.getInputs(), sample.getDesiredOutputs()));
                } else {
                    testSamples.add(sample);
                }
            }
        }

        return new SplitResult(trainSet, testSamples);
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

    private static void normalizeInputs(List<double[]> inputs, NormalizationType type) {
        if (type == null || type == NormalizationType.NONE || inputs.isEmpty()) {
            return;
        }

        int dims = inputs.get(0).length;
        double[] min = new double[dims];
        double[] max = new double[dims];

        for (int d = 0; d < dims; d++) {
            min[d] = Double.POSITIVE_INFINITY;
            max[d] = Double.NEGATIVE_INFINITY;
        }

        for (double[] in : inputs) {
            for (int d = 0; d < dims; d++) {
                if (in[d] < min[d]) min[d] = in[d];
                if (in[d] > max[d]) max[d] = in[d];
            }
        }

        for (double[] in : inputs) {
            for (int d = 0; d < dims; d++) {
                double range = max[d] - min[d];
                if (range > 1e-9) {
                    if (type == NormalizationType.MIN_MAX_0_1) {
                        in[d] = (in[d] - min[d]) / range;
                    } else if (type == NormalizationType.MIN_MAX_MINUS1_1) {
                        in[d] = 2.0 * ((in[d] - min[d]) / range) - 1.0;
                    }
                } else {
                    in[d] = 0.0;
                }
            }
        }
    }
}
