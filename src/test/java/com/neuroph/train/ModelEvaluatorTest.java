package com.neuroph.train;

import com.neuroph.train.client.worker.ModelEvaluator;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.EvaluationMetrics;
import com.neuroph.train.common.model.TaskType;
import com.neuroph.train.common.util.DatasetParser.DataSample;
import org.junit.jupiter.api.Test;
import org.neuroph.nnet.MultiLayerPerceptron;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ModelEvaluatorTest {

    @Test
    public void testMetricsCalculation() {
        MultiLayerPerceptron mlp = new MultiLayerPerceptron(2, 4, 1);

        List<DataSample> testSamples = new ArrayList<>();
        testSamples.add(new DataSample(new double[]{0.1, 0.2}, new double[]{1.0}, 1));
        testSamples.add(new DataSample(new double[]{0.8, 0.9}, new double[]{0.0}, 0));
        testSamples.add(new DataSample(new double[]{0.3, 0.4}, new double[]{1.0}, 1));
        testSamples.add(new DataSample(new double[]{0.7, 0.6}, new double[]{0.0}, 0));

        DatasetMetadata meta = new DatasetMetadata();
        meta.setTaskType(TaskType.CLASSIFICATION);
        meta.setClassLabels(List.of("NEGATIVO", "POSITIVO"));

        EvaluationMetrics metrics = ModelEvaluator.evaluate(mlp, testSamples, meta, 1.25, 250, 0.005);

        assertNotNull(metrics);
        assertEquals(1.25, metrics.getTiempoSeg());
        assertEquals(250, metrics.getIteraciones());
        assertEquals(0.005, metrics.getErrorFinal());

        assertTrue(metrics.getMse() >= 0.0, "MSE debe ser no negativo");
        assertTrue(metrics.getRmse() >= 0.0, "RMSE debe ser no negativo");
        assertTrue(metrics.getMae() >= 0.0, "MAE debe ser no negativo");
        assertTrue(metrics.getPercentil90() >= 0.0, "Percentil90 debe ser no negativo");
        assertTrue(metrics.getMaxError() >= metrics.getMinError(), "MaxError >= MinError");
        assertTrue(metrics.getAccuracy() >= 0.0 && metrics.getAccuracy() <= 100.0, "Accuracy entre 0 y 100");
    }
}
