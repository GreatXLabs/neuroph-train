package com.neuroph.train;

import com.neuroph.train.common.model.ColumnConfig;
import com.neuroph.train.common.model.ColumnRole;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.NormalizationType;
import com.neuroph.train.common.model.TaskType;
import com.neuroph.train.common.util.DatasetParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DatasetParserTest {

    @Test
    public void testParseAndSplitStandard() throws IOException {
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
        meta.setHasHeader(true);
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

    @Test
    public void testParseWithoutHeader() throws IOException {
        // Archivo puramente numérico sin cabeceras
        String csv = "10.0,20.0,0\n" +
                "15.0,25.0,0\n" +
                "30.0,40.0,1\n" +
                "35.0,45.0,1\n" +
                "50.0,60.0,0\n" +
                "55.0,65.0,1\n";

        DatasetMetadata meta = new DatasetMetadata();
        meta.setId("no-header-ds");
        meta.setHasHeader(false);
        meta.setInputColumns(List.of(0, 1));
        meta.setOutputColumns(List.of(2));
        meta.setTaskType(TaskType.CLASSIFICATION);

        DatasetParser.SplitResult split = DatasetParser.parseAndSplit(csv, meta, 0.60, 42L);

        // Todas las 6 filas deben ser procesadas como datos
        int totalRows = split.getTrainSet().size() + split.getTestSamples().size();
        assertEquals(6, totalRows, "Debe procesar 6 filas de datos numéricos");
    }

    @Test
    public void testGranularNormalization() throws IOException {
        String csv = "colA,colB,colC,target\n" +
                "10,100,50,0\n" +
                "20,200,60,0\n" +
                "30,300,70,1\n" +
                "40,400,80,1\n" +
                "50,500,90,0\n" +
                "60,600,100,1\n";

        DatasetMetadata meta = new DatasetMetadata();
        meta.setId("granular-ds");
        meta.setHasHeader(true);

        List<ColumnConfig> configs = new ArrayList<>();
        configs.add(new ColumnConfig(0, "colA", ColumnRole.INPUT, NormalizationType.MIN_MAX_0_1));
        configs.add(new ColumnConfig(1, "colB", ColumnRole.INPUT, NormalizationType.MIN_MAX_MINUS1_1));
        configs.add(new ColumnConfig(2, "colC", ColumnRole.INPUT, NormalizationType.Z_SCORE));
        configs.add(new ColumnConfig(3, "target", ColumnRole.OUTPUT, NormalizationType.NONE));
        meta.setColumnConfigs(configs);

        DatasetParser.SplitResult split = DatasetParser.parseAndSplit(csv, meta, 0.50, 42L);

        assertNotNull(split.getTrainSet());
        assertEquals(3, meta.getInputCount());

        // Verificar que colA está en [0, 1] y colB en [-1, 1]
        for (var row : split.getTrainSet().getRows()) {
            double[] in = row.getInput();
            assertTrue(in[0] >= -1e-6 && in[0] <= 1.0 + 1e-6, "colA debe estar en [0, 1]: " + in[0]);
            assertTrue(in[1] >= -1.0 - 1e-6 && in[1] <= 1.0 + 1e-6, "colB debe estar en [-1, 1]: " + in[1]);
        }
    }

    @Test
    public void testDataAugmentation() throws IOException {
        String csv = "s1,s2,label\n" +
                "1.0,2.0,0\n" +
                "1.5,2.5,0\n" +
                "3.0,4.0,1\n" +
                "3.5,4.5,1\n";

        DatasetMetadata meta = new DatasetMetadata();
        meta.setId("aug-ds");
        meta.setHasHeader(true);
        meta.setInputColumns(List.of(0, 1));
        meta.setOutputColumns(List.of(2));
        meta.setTaskType(TaskType.CLASSIFICATION);

        // Sin augmentation:
        DatasetParser.SplitResult noAug = DatasetParser.parseAndSplit(csv, meta, 0.50, 42L, false, 0, 0.0);
        int trainRowsBase = noAug.getTrainSet().size();
        int testRowsBase = noAug.getTestSamples().size();

        // Con augmentation: factor 2 (2 copias adicionales por muestra en train)
        DatasetParser.SplitResult withAug = DatasetParser.parseAndSplit(csv, meta, 0.50, 42L, true, 2, 0.05);

        // El trainSet debe tener (1 + factor) * trainRowsBase muestras
        assertEquals(trainRowsBase * 3, withAug.getTrainSet().size(), "El conjunto de entrenamiento debe triplicarse con factor 2");

        // El testSet debe permanecer intacto sin copias sintéticas
        assertEquals(testRowsBase, withAug.getTestSamples().size(), "El conjunto de prueba debe conservar su tamaño original");
    }

    @Test
    public void testInspectCsv() throws IOException {
        String csv = "dist_left;dist_center;dist_right;command\n" +
                "12.5;30.0;15.2;AVANZAR\n" +
                "5.0;10.2;8.0;GIRAR_IZQ\n" +
                "8.0;7.5;22.0;GIRAR_DER\n";

        DatasetParser.CsvInspectionResult res = DatasetParser.inspectCsv(csv, true);

        assertEquals(";", res.getDelimiter(), "Debe auto-detectar delimitador punto y coma");
        assertEquals(4, res.getColumnCount(), "Debe detectar 4 columnas");
        assertEquals(3, res.getRowCount(), "Debe contar 3 filas de datos");
        assertEquals("dist_left", res.getHeaders().get(0));
        assertEquals("command", res.getHeaders().get(3));

        // Verificar pre-llenado de roles: 0..2 INPUT, 3 OUTPUT
        assertEquals(ColumnRole.INPUT, res.getColumnConfigs().get(0).getRole());
        assertEquals(ColumnRole.INPUT, res.getColumnConfigs().get(1).getRole());
        assertEquals(ColumnRole.INPUT, res.getColumnConfigs().get(2).getRole());
        assertEquals(ColumnRole.OUTPUT, res.getColumnConfigs().get(3).getRole());
        assertEquals(NormalizationType.MIN_MAX_0_1, res.getColumnConfigs().get(0).getNormalization());
        assertEquals(NormalizationType.NONE, res.getColumnConfigs().get(3).getNormalization());
    }
}
