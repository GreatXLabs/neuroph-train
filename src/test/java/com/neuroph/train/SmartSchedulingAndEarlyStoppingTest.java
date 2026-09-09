package com.neuroph.train;

import com.neuroph.train.client.worker.BenchmarkUtil;
import com.neuroph.train.client.worker.NeurophTrainer;
import com.neuroph.train.common.model.NetworkConfig;
import com.neuroph.train.common.model.TrainingTask;
import com.neuroph.train.server.orchestrator.WorkerSession;
import com.neuroph.train.server.queue.TaskManager;
import org.junit.jupiter.api.Test;
import org.neuroph.core.data.DataSet;
import org.neuroph.core.data.DataSetRow;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SmartSchedulingAndEarlyStoppingTest {

    @Test
    public void testBenchmarkUtilPerformance() {
        long start = System.currentTimeMillis();
        double score = BenchmarkUtil.runBenchmark();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(score >= 100.0, "El score de benchmark debe ser positivo y representativo: " + score);
        assertTrue(elapsed < 1000, "El micro-benchmark debe ejecutarse en menos de 1 segundo: " + elapsed + "ms");
    }

    @Test
    public void testSmartPrioritySchedulingLpt() {
        TaskManager manager = new TaskManager();

        TrainingTask lightTask = new TrainingTask("c1", "ds1", new NetworkConfig(3, List.of(4), 1, "SIGMOID", 0.1, 0.2, 500, 0.01), 0);
        lightTask.setEstimatedComplexity(2000.0);

        TrainingTask heavyTask = new TrainingTask("c1", "ds1", new NetworkConfig(3, List.of(32, 16), 1, "SIGMOID", 0.1, 0.2, 3000, 0.01), 0);
        heavyTask.setEstimatedComplexity(85000.0);

        TrainingTask mediumTask = new TrainingTask("c1", "ds1", new NetworkConfig(3, List.of(12), 1, "SIGMOID", 0.1, 0.2, 1000, 0.01), 0);
        mediumTask.setEstimatedComplexity(15000.0);

        // Encolar en orden desordenado
        manager.enqueueTask(lightTask);
        manager.enqueueTask(heavyTask);
        manager.enqueueTask(mediumTask);

        // El poll debe extraer en estricto orden descendente de complejidad (LPT)
        TrainingTask first = manager.pollTask();
        assertNotNull(first);
        assertEquals(85000.0, first.getEstimatedComplexity(), "La tarea más pesada debe despacharse primero");

        TrainingTask second = manager.pollTask();
        assertNotNull(second);
        assertEquals(15000.0, second.getEstimatedComplexity(), "La tarea intermedia debe despacharse en segundo lugar");

        TrainingTask third = manager.pollTask();
        assertNotNull(third);
        assertEquals(2000.0, third.getEstimatedComplexity(), "La tarea más liviana debe despacharse al final");
    }

    @Test
    public void testEarlyStoppingInNeurophTrainer() {
        NeurophTrainer trainer = new NeurophTrainer();

        // Dataset pequeño con error inalcanzable para provocar meseta
        DataSet ds = new DataSet(2, 1);
        ds.add(new DataSetRow(new double[]{0.0, 0.0}, new double[]{0.0}));
        ds.add(new DataSetRow(new double[]{0.0, 1.0}, new double[]{1.0}));
        ds.add(new DataSetRow(new double[]{1.0, 0.0}, new double[]{1.0}));
        ds.add(new DataSetRow(new double[]{1.0, 1.0}, new double[]{0.0}));

        NetworkConfig config = new NetworkConfig(2, List.of(3), 1, "SIGMOID", 0.0001, 0.0, 3000, 0.000001);
        TrainingTask task = new TrainingTask("c1", "ds1", config, 0);
        task.setPatience(20); // Parar tras 20 épocas sin mejora significativa

        NeurophTrainer.TrainingResult res = trainer.train(task, ds, null);

        assertNotNull(res);
        assertNotNull(res.getNeuralNetwork());
        // El early stopping debe haber frenado mucho antes de las 3000 iteraciones máximas
        assertTrue(res.getIterations() < 1000, "El early stopping debe detener el entrenamiento anticipadamente ante meseta. Iteraciones: " + res.getIterations());
        assertTrue(res.getNeuralNetwork().getWeights().length > 0, "La red debe conservar los pesos calculados");
    }
}
