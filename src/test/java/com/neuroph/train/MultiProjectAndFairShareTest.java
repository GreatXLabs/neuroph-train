package com.neuroph.train;

import com.neuroph.train.client.network.ServerConnection;
import com.neuroph.train.common.model.CampaignConfig;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.EvaluationMetrics;
import com.neuroph.train.common.model.Project;
import com.neuroph.train.common.model.TaskResult;
import com.neuroph.train.common.model.TrainingTask;
import com.neuroph.train.common.protocol.JsonUtil;
import com.neuroph.train.server.ServerConfig;
import com.neuroph.train.server.heuristic.CampaignOrchestrator;
import com.neuroph.train.server.orchestrator.TrainingServer;
import com.neuroph.train.server.orchestrator.WorkerRegistry;
import com.neuroph.train.server.queue.TaskManager;
import com.neuroph.train.server.storage.StorageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultiProjectAndFairShareTest {

    @Test
    public void testProjectStorageAndDatasetsAssociation(@TempDir Path tempDir) throws IOException {
        StorageManager storage = new StorageManager(tempDir.toFile());

        // Default project should be automatically created
        List<Project> projects = storage.listProjects();
        assertEquals(1, projects.size());
        assertEquals("default-project", projects.get(0).getId());

        // Create new project
        Project p1 = storage.getOrCreateProject("Robotica 2026");
        assertNotNull(p1.getId());
        assertEquals("Robotica 2026", p1.getName());

        // Save dataset in project p1
        DatasetMetadata d1 = new DatasetMetadata();
        d1.setId("ds-sensors-v1");
        d1.setName("Sensors Original");
        d1.setProjectId(p1.getId());
        storage.saveDataset(d1, "1,2,3,4\n5,6,7,8");

        // Save another dataset in project p1
        DatasetMetadata d2 = new DatasetMetadata();
        d2.setId("ds-sensors-v2");
        d2.setName("Sensors Ruido 5%");
        d2.setProjectId(p1.getId());
        storage.saveDataset(d2, "1.1,2.1,3,4\n5.1,6.1,7,8");

        // Save dataset in default project
        DatasetMetadata d3 = new DatasetMetadata();
        d3.setId("ds-other");
        d3.setName("Other");
        d3.setProjectId("default-project");
        storage.saveDataset(d3, "0,1\n1,0");

        List<DatasetMetadata> p1Datasets = storage.listDatasetsByProject(p1.getId());
        assertEquals(2, p1Datasets.size());

        List<DatasetMetadata> defDatasets = storage.listDatasetsByProject("default-project");
        assertEquals(1, defDatasets.size());

        // Test reloading from disk
        StorageManager reloaded = new StorageManager(tempDir.toFile());
        Project reloadedP1 = reloaded.getProject(p1.getId());
        assertNotNull(reloadedP1);
        assertEquals("Robotica 2026", reloadedP1.getName());
        assertEquals(2, reloadedP1.getDatasetIds().size());
    }

    @Test
    public void testFairShareRoundRobinScheduling() {
        TaskManager tm = new TaskManager();

        // Enqueue 3 tasks for Campaign A
        TrainingTask tA1 = new TrainingTask();
        tA1.setTaskId("tA1");
        tA1.setCampaignId("campA");
        tA1.setEstimatedComplexity(100.0);

        TrainingTask tA2 = new TrainingTask();
        tA2.setTaskId("tA2");
        tA2.setCampaignId("campA");
        tA2.setEstimatedComplexity(100.0);

        TrainingTask tA3 = new TrainingTask();
        tA3.setTaskId("tA3");
        tA3.setCampaignId("campA");
        tA3.setEstimatedComplexity(100.0);

        // Enqueue 3 tasks for Campaign B
        TrainingTask tB1 = new TrainingTask();
        tB1.setTaskId("tB1");
        tB1.setCampaignId("campB");
        tB1.setEstimatedComplexity(100.0);

        TrainingTask tB2 = new TrainingTask();
        tB2.setTaskId("tB2");
        tB2.setCampaignId("campB");
        tB2.setEstimatedComplexity(100.0);

        TrainingTask tB3 = new TrainingTask();
        tB3.setTaskId("tB3");
        tB3.setCampaignId("campB");
        tB3.setEstimatedComplexity(100.0);

        tm.enqueueTasks(List.of(tA1, tA2, tA3));
        tm.enqueueTasks(List.of(tB1, tB2, tB3));

        assertEquals(6, tm.getPendingCount());

        // Polling should alternate between campA and campB
        TrainingTask polled1 = tm.pollTask();
        TrainingTask polled2 = tm.pollTask();
        TrainingTask polled3 = tm.pollTask();
        TrainingTask polled4 = tm.pollTask();

        assertNotNull(polled1);
        assertNotNull(polled2);
        assertNotNull(polled3);
        assertNotNull(polled4);

        // Verify round-robin fairness: polled1 and polled2 must belong to different campaigns
        assertFalse(polled1.getCampaignId().equals(polled2.getCampaignId()),
                "Fair-Share dispatcher must alternate between campaigns");
        assertFalse(polled2.getCampaignId().equals(polled3.getCampaignId()),
                "Fair-Share dispatcher must alternate between campaigns");
    }

    @Test
    public void testLeaderboardFilteringAndComparison(@TempDir Path tempDir) throws IOException {
        StorageManager storage = new StorageManager(tempDir.toFile());

        Project p = storage.getOrCreateProject("Vision");

        DatasetMetadata d1 = new DatasetMetadata();
        d1.setId("ds-v1");
        d1.setName("Version 1");
        d1.setProjectId(p.getId());
        storage.saveDataset(d1, "1,2\n3,4");

        DatasetMetadata d2 = new DatasetMetadata();
        d2.setId("ds-v2");
        d2.setName("Version 2 Mutada");
        d2.setProjectId(p.getId());
        storage.saveDataset(d2, "1,2\n3,4");

        CampaignConfig c1 = new CampaignConfig();
        c1.setCampaignId("camp-1");
        c1.setProjectId(p.getId());
        c1.setDatasetId(d1.getId());
        c1.setDatasetName(d1.getName());
        storage.saveCampaign(c1);

        CampaignConfig c2 = new CampaignConfig();
        c2.setCampaignId("camp-2");
        c2.setProjectId(p.getId());
        c2.setDatasetId(d2.getId());
        c2.setDatasetName(d2.getName());
        storage.saveCampaign(c2);

        // Result for camp-1 (ds-v1): Acc 85%, RMSE 0.15
        TaskResult r1 = new TaskResult();
        r1.setTaskId("task-1");
        r1.setCampaignId("camp-1");
        r1.setProjectId(p.getId());
        r1.setDatasetId(d1.getId());
        r1.setDatasetName(d1.getName());
        EvaluationMetrics m1 = new EvaluationMetrics();
        m1.setAccuracy(85.0);
        m1.setRmse(0.15);
        r1.setMetrics(m1);
        storage.saveModelResult(r1);

        // Result for camp-2 (ds-v2): Acc 92%, RMSE 0.08
        TaskResult r2 = new TaskResult();
        r2.setTaskId("task-2");
        r2.setCampaignId("camp-2");
        r2.setProjectId(p.getId());
        r2.setDatasetId(d2.getId());
        r2.setDatasetName(d2.getName());
        EvaluationMetrics m2 = new EvaluationMetrics();
        m2.setAccuracy(92.0);
        m2.setRmse(0.08);
        r2.setMetrics(m2);
        storage.saveModelResult(r2);

        // Leaderboard by dataset
        List<TaskResult> lbD1 = storage.getLeaderboardByDataset(d1.getId());
        assertEquals(1, lbD1.size());
        assertEquals("task-1", lbD1.get(0).getTaskId());

        List<TaskResult> lbD2 = storage.getLeaderboardByDataset(d2.getId());
        assertEquals(1, lbD2.size());
        assertEquals("task-2", lbD2.get(0).getTaskId());

        // Leaderboard by project (should have both, sorted by Acc descending)
        List<TaskResult> lbProj = storage.getLeaderboardByProject(p.getId());
        assertEquals(2, lbProj.size());
        assertEquals("task-2", lbProj.get(0).getTaskId(), "Higher accuracy must be ranked first");
        assertEquals("task-1", lbProj.get(1).getTaskId());

        // Best model per dataset
        Map<String, TaskResult> bestMap = storage.getBestModelPerDataset(p.getId());
        assertEquals(2, bestMap.size());
        assertEquals("task-1", bestMap.get(d1.getId()).getTaskId());
        assertEquals("task-2", bestMap.get(d2.getId()).getTaskId());
    }

    @Test
    public void testNetworkProjectProtocolIntegration(@TempDir Path tempDir) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }

        ServerConfig serverConfig = new ServerConfig();
        try {
            var fPort = ServerConfig.class.getDeclaredField("port");
            fPort.setAccessible(true);
            fPort.set(serverConfig, port);

            var fDir = ServerConfig.class.getDeclaredField("storageDir");
            fDir.setAccessible(true);
            fDir.set(serverConfig, tempDir.toFile());

            var fToken = ServerConfig.class.getDeclaredField("adminToken");
            fToken.setAccessible(true);
            fToken.set(serverConfig, "test-secret");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        StorageManager sm = new StorageManager(tempDir.toFile());
        TaskManager tm = new TaskManager();
        WorkerRegistry wr = new WorkerRegistry(tm, 10);
        CampaignOrchestrator co = new CampaignOrchestrator(sm, tm);

        TrainingServer server = new TrainingServer(serverConfig, sm, tm, wr, co);
        server.start();

        try {
            ServerConnection client = new ServerConnection("127.0.0.1", port, "TestClient", 2);
            client.connect();

            CompletableFuture<Boolean> authFuture = client.authenticateAdmin("test-secret");
            assertTrue(authFuture.get(5, TimeUnit.SECONDS));

            // Create project over protocol
            CompletableFuture<String> createFuture = client.createProject("Automated Navigation");
            String projJson = createFuture.get(5, TimeUnit.SECONDS);
            Project proj = JsonUtil.fromJson(projJson, Project.class);
            assertNotNull(proj);
            assertEquals("Automated Navigation", proj.getName());

            // List projects over protocol
            CompletableFuture<String> listFuture = client.listProjects();
            String listJson = listFuture.get(5, TimeUnit.SECONDS);
            Project[] pArray = JsonUtil.fromJson(listJson, Project[].class);
            assertTrue(pArray.length >= 2); // default-project + Automated Navigation

            // Query project leaderboard
            CompletableFuture<String> lbFuture = client.getProjectLeaderboard(proj.getId());
            String lbJson = lbFuture.get(5, TimeUnit.SECONDS);
            TaskResult[] lbResults = JsonUtil.fromJson(lbJson, TaskResult[].class);
            assertNotNull(lbResults);
            assertEquals(0, lbResults.length);

            client.disconnect("Fin test");
        } finally {
            server.stop();
        }
    }
}
