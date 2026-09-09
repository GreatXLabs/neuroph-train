package com.neuroph.train;

import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.NormalizationType;
import com.neuroph.train.common.model.TaskType;
import com.neuroph.train.common.util.DatasetParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DatasetParserTest {

    @Test
    public void testParseAndSplit() throws IOException {
        String csv = "f1,f2,target\n" +
                "10,20,0\n" +
                "15,25,0\n" +
                "30,40,1\n" +
                "35,45,1\n" +
                "50,60,0\n" +
                "55,65,0\n" +
                "70,80,1\n" +
                "75,85,1\n" +
                "90,95,0\n" +
                "95,99,1\n";

        DatasetMetadata meta = new DatasetMetadata();
        meta.setId("test-ds");
        meta.setInputColumns(List.of(0, 1));
        meta.setOutputColumns(List.of(2));
        meta.setTaskType(TaskType.CLASSIFICATION);
        meta.setNormalization(NormalizationType.MIN_MAX_0_1);

        DatasetParser.SplitResult split = DatasetParser.parseAndSplit(csv, meta, 0.70, 123L);

        assertNotNull(split.getTrainSet());
        assertNotNull(split.getTestSamples());

        // Total 10 filas (5 clase 0, 5 clase 1) -> Estratificado: 4 de cada una = 8 train, 2 test
        assertEquals(8, split.getTrainSet().size());
        assertEquals(2, split.getTestSamples().size());

        // Verificar normalización en [0, 1]
        double[] firstIn = split.getTestSamples().get(0).getInputs();
        for (double v : firstIn) {
            assertTrue(v >= 0.0 && v <= 1.0, "Valor fuera de rango [0, 1]: " + v);
        }
    }
}
