package com.neuroph.train.client.worker;

import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.EvaluationMetrics;
import com.neuroph.train.common.model.TaskType;
import com.neuroph.train.common.util.DatasetParser.DataSample;
import org.neuroph.core.NeuralNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Calculador de métricas de evaluación del modelo sobre el conjunto de test.
 */
public final class ModelEvaluator {

    private ModelEvaluator() {
    }

    public static EvaluationMetrics evaluate(NeuralNetwork<?> network,
                                            List<DataSample> testSamples,
                                            DatasetMetadata metadata,
                                            double trainingTimeSeconds,
                                            int iterations,
                                            double finalTrainingError) {
        EvaluationMetrics metrics = new EvaluationMetrics();
        metrics.setTiempoSeg(Math.round(trainingTimeSeconds * 100.0) / 100.0);
        metrics.setIteraciones(iterations);
        metrics.setErrorFinal(finalTrainingError);
        metrics.setTestSampleCount(testSamples.size());

        if (testSamples.isEmpty()) {
            return metrics;
        }

        List<Double> absoluteErrors = new ArrayList<>(testSamples.size());
        double sumSquaredError = 0.0;
        double sumAbsoluteError = 0.0;
        double sumMape = 0.0;
        double maxErr = Double.NEGATIVE_INFINITY;
        double minErr = Double.POSITIVE_INFINITY;

        // Para R2
        double sumY = 0.0;
        int totalOutputsCount = 0;

        // Para Clasificación
        int correctClassifications = 0;
        int numClasses = metadata.getClassLabels() != null ? metadata.getClassLabels().size() : 0;
        if (numClasses <= 1 && metadata.getTaskType() == TaskType.CLASSIFICATION) {
            numClasses = 2; // Binaria
        }
        int[][] confusion = (metadata.getTaskType() == TaskType.CLASSIFICATION && numClasses > 0)
                ? new int[numClasses][numClasses] : null;

        for (DataSample sample : testSamples) {
            network.setInput(sample.getInputs());
            network.calculate();
            double[] predicted = network.getOutput();
            double[] actual = sample.getDesiredOutputs();

            for (int i = 0; i < actual.length; i++) {
                double diff = actual[i] - predicted[i];
                double absDiff = Math.abs(diff);

                sumSquaredError += diff * diff;
                sumAbsoluteError += absDiff;
                absoluteErrors.add(absDiff);

                if (absDiff > maxErr) maxErr = absDiff;
                if (absDiff < minErr) minErr = absDiff;

                double denom = Math.max(Math.abs(actual[i]), 1e-6);
                sumMape += (absDiff / denom);

                sumY += actual[i];
                totalOutputsCount++;
            }

            // Evaluación de Clasificación
            if (metadata.getTaskType() == TaskType.CLASSIFICATION) {
                int predClass;
                int actClass;

                if (numClasses > 2) {
                    predClass = argMax(predicted);
                    actClass = sample.getClassIndex() >= 0 ? sample.getClassIndex() : argMax(actual);
                } else {
                    // Binaria
                    predClass = (predicted[0] >= 0.5) ? 1 : 0;
                    actClass = (actual[0] >= 0.5) ? 1 : 0;
                }

                if (predClass == actClass) {
                    correctClassifications++;
                }

                if (confusion != null && actClass >= 0 && actClass < numClasses && predClass >= 0 && predClass < numClasses) {
                    confusion[actClass][predClass]++;
                }
            }
        }

        // Métricas de error
        double mse = sumSquaredError / totalOutputsCount;
        double rmse = Math.sqrt(mse);
        double mae = sumAbsoluteError / totalOutputsCount;
        double mape = (sumMape / totalOutputsCount) * 100.0;

        metrics.setMse(mse);
        metrics.setRmse(rmse);
        metrics.setMae(mae);
        metrics.setMape(mape);
        metrics.setMaxError(maxErr != Double.NEGATIVE_INFINITY ? maxErr : 0.0);
        metrics.setMinError(minErr != Double.POSITIVE_INFINITY ? minErr : 0.0);

        // Percentil 90
        Collections.sort(absoluteErrors);
        int p90Index = (int) Math.round(0.90 * (absoluteErrors.size() - 1));
        metrics.setPercentil90(absoluteErrors.get(p90Index));

        // R2 (Coeficiente de determinación)
        double meanY = sumY / totalOutputsCount;
        double ssTot = 0.0;
        for (DataSample sample : testSamples) {
            for (double act : sample.getDesiredOutputs()) {
                double dev = act - meanY;
                ssTot += dev * dev;
            }
        }
        double r2 = (ssTot > 1e-9) ? 1.0 - (sumSquaredError / ssTot) : 1.0;
        metrics.setR2(r2);

        // Métricas de Clasificación
        if (metadata.getTaskType() == TaskType.CLASSIFICATION) {
            double acc = ((double) correctClassifications / testSamples.size()) * 100.0;
            metrics.setAccuracy(acc);
            metrics.setConfusionMatrix(confusion);
            metrics.setClassLabels(metadata.getClassLabels());

            // F1 Macro
            if (confusion != null) {
                metrics.setF1Score(calculateMacroF1(confusion, numClasses));
            }
        } else {
            // Si es regresión, accuracy se puede asociar a 1 - MAPE acotado
            metrics.setAccuracy(Math.max(0.0, 100.0 - mape));
        }

        return metrics;
    }

    private static int argMax(double[] array) {
        int bestIdx = 0;
        double maxVal = array[0];
        for (int i = 1; i < array.length; i++) {
            if (array[i] > maxVal) {
                maxVal = array[i];
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static double calculateMacroF1(int[][] confusion, int numClasses) {
        double totalF1 = 0.0;
        int validClasses = 0;

        for (int c = 0; c < numClasses; c++) {
            int tp = confusion[c][c];
            int fn = 0;
            int fp = 0;

            for (int j = 0; j < numClasses; j++) {
                if (j != c) {
                    fn += confusion[c][j];
                    fp += confusion[j][c];
                }
            }

            double precision = (tp + fp > 0) ? (double) tp / (tp + fp) : 0.0;
            double recall = (tp + fn > 0) ? (double) tp / (tp + fn) : 0.0;

            if (precision + recall > 0) {
                double f1 = 2.0 * (precision * recall) / (precision + recall);
                totalF1 += f1;
                validClasses++;
            }
        }

        return validClasses > 0 ? (totalF1 / validClasses) : 0.0;
    }
}
