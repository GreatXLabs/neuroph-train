package com.neuroph.train;

import com.neuroph.train.common.model.CampaignConfig;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.EvaluationMetrics;
import com.neuroph.train.common.model.NetworkConfig;
import com.neuroph.train.common.model.TaskResult;
import com.neuroph.train.common.util.NetworkSerializer;
import com.neuroph.train.server.storage.StorageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neuroph.nnet.MultiLayerPerceptron;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class StorageManagerTest {

    @Test
    public void testSaveAndRetrieveDataset(@TempDir File tempDir) throws IOException {
        StorageManager sm = new StorageManager(tempDir);

        DatasetMetadata meta = new DatasetMetadata();
        meta.setId("ds-1");
        meta.setName("Test Dataset");
        meta.setInputColumns(List.of(0, 1));
        meta.setOutputColumns(List.of(2));

        String csv = "1,2,0\n3,4,1";
        sm.saveDataset(meta, csv);

        DatasetMetadata loaded = sm.getDataset("ds-1");
        assertNotNull(loaded);
        assertEquals("Test Dataset", loaded.getName());
        assertEquals(csv, loaded.getCsvContent());
    }

    @Test
    public void testSaveAndRetrieveModelResult(@TempDir File tempDir) throws IOException {
        StorageManager sm = new StorageManager(tempDir);

        MultiLayerPerceptron mlp = new MultiLayerPerceptron(2, 3, 1);
        String base64 = NetworkSerializer.toBase64(mlp);

        TaskResult result = new TaskResult();
        result.setTaskId("task-100");
        result.setCampaignId("camp-1");
        result.setWorkerName("TestWorker");
        result.setNetworkConfig(new NetworkConfig(2, List.of(3), 1, "SIGMOID", 0.1, 0.2, 500, 0.01));
        EvaluationMetrics metrics = new EvaluationMetrics();
        metrics.setAccuracy(92.5);
        metrics.setRmse(0.12);
        result.setMetrics(metrics);
        result.setNnetBase64(base64);
        result.setSuccess(true);

        sm.saveModelResult(result);

        List<TaskResult> lb = sm.getLeaderboard("camp-1");
        assertEquals(1, lb.size());
        assertEquals("task-100", lb.get(0).getTaskId());

        byte[] nnetBytes = sm.getModelNnetBytes("camp-1", "task-100");
        assertNotNull(nnetBytes);
        assertTrue(nnetBytes.length > 0);
    }
}
