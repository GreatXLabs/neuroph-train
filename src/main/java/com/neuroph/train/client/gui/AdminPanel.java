package com.neuroph.train.client.gui;

import com.neuroph.train.client.network.ServerConnection;
import com.neuroph.train.common.model.CampaignConfig;
import com.neuroph.train.common.model.ColumnConfig;
import com.neuroph.train.common.model.ColumnRole;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.EvaluationMetrics;
import com.neuroph.train.common.model.HeuristicType;
import com.neuroph.train.common.model.NormalizationType;
import com.neuroph.train.common.model.TaskResult;
import com.neuroph.train.common.model.TaskType;
import com.neuroph.train.common.protocol.JsonUtil;
import com.neuroph.train.common.util.DatasetParser;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Panel de Administración protegido por token: subida de datasets, configuración de campañas
 * y tabla de clasificación (Leaderboard) con descarga de modelos .nnet.
 */
public class AdminPanel extends JPanel {

    private final ServerConnection connection;

    private JPanel cardContainer;
    private CardLayout cardLayout;

    // Vista Login
    private JPasswordField tokenField;
    private JButton loginButton;
    private JLabel loginStatusLabel;

    // Vista Admin Dashboard
    private JTabbedPane adminTabs;

    // Subpestaña Datasets
    private JTextField datasetNameField;
    private JComboBox<TaskType> taskTypeCombo;
    private JCheckBox hasHeaderCheckbox;
    private JTextField classLabelsField;
    private JComboBox<NormalizationType> normCombo; // fallback global
    private File selectedCsvFile;
    private String cachedCsvContent;
    private JTable columnsTable;
    private DefaultTableModel columnsModel;
    private JTable previewTable;
    private DefaultTableModel previewModel;
    private JLabel selectedFileLabel;
    private JButton uploadButton;

    // Subpestaña Campaña
    private JComboBox<String> datasetCombo;
    private JComboBox<HeuristicType> heuristicCombo;
    private JSpinner minLayersSpinner;
    private JSpinner maxLayersSpinner;
    private JSpinner minNeuronsSpinner;
    private JSpinner maxNeuronsSpinner;
    private JSpinner maxIterSpinner;
    private JSpinner targetErrorSpinner;
    private JSpinner patienceSpinner;
    private JCheckBox enableAugmentationCheckbox;
    private JSpinner augmentationFactorSpinner;
    private JSpinner augmentationNoiseSpinner;
    private JSpinner maxTasksSpinner;
    private JButton startCampaignBtn;
    private JButton pauseCampaignBtn;
    private JButton stopCampaignBtn;
    private JLabel campaignStatusLabel;
    private JLabel workersStatusLabel;

    // Subpestaña Leaderboard
    private JTable leaderboardTable;
    private DefaultTableModel leaderboardModel;
    private JButton refreshLbButton;
    private JButton downloadNnetButton;
    private List<TaskResult> currentLeaderboard = new ArrayList<>();

    public AdminPanel(ServerConnection connection) {
        this.connection = connection;
        setLayout(new BorderLayout());

        cardLayout = new CardLayout();
        cardContainer = new JPanel(cardLayout);

        buildLoginView();
        buildDashboardView();

        add(cardContainer, BorderLayout.CENTER);
        cardLayout.show(cardContainer, "LOGIN");
    }

    private void buildLoginView() {
        JPanel loginPanel = new JPanel(new GridBagLayout());
        loginPanel.setBorder(new EmptyBorder(40, 40, 40, 40));

        JPanel box = new JPanel(new GridLayout(4, 1, 10, 10));
        box.setBorder(new TitledBorder("Autenticación de Administrador (Dokploy / VPS)"));
        box.setPreferredSize(new Dimension(380, 200));

        box.add(new JLabel("Introduce el ADMIN_TOKEN configurado en el servidor:", SwingConstants.CENTER));
        tokenField = new JPasswordField(15);
        tokenField.setToolTipText("Introduce la clave secreta ADMIN_TOKEN configurada en el servidor (Dokploy/VPS)");
        box.add(tokenField);

        loginButton = new JButton("Acceder al Panel de Control");
        loginButton.setBackground(new Color(60, 100, 180));
        loginButton.setForeground(Color.WHITE);
        loginButton.setToolTipText("Valida las credenciales de administrador para desbloquear el panel de control");
        box.add(loginButton);

        loginStatusLabel = new JLabel("Requiere conexión activa previa al servidor", SwingConstants.CENTER);
        loginStatusLabel.setForeground(Color.GRAY);
        box.add(loginStatusLabel);

        loginButton.addActionListener(e -> attemptLogin());

        loginPanel.add(box);
        cardContainer.add(loginPanel, "LOGIN");
    }

    private void attemptLogin() {
        if (!connection.isConnected()) {
            loginStatusLabel.setText("Primero conecta el cliente en la pestaña Worker");
            loginStatusLabel.setForeground(Color.RED);
            return;
        }

        String token = new String(tokenField.getPassword()).trim();
        if (token.isEmpty()) {
            loginStatusLabel.setText("El token no puede estar vacío");
            loginStatusLabel.setForeground(Color.RED);
            return;
        }

        loginStatusLabel.setText("Validando token con el servidor...");
        loginStatusLabel.setForeground(Color.ORANGE);

        connection.authenticateAdmin(token).thenAccept(ok -> SwingUtilities.invokeLater(() -> {
            if (ok) {
                cardLayout.show(cardContainer, "DASHBOARD");
                refreshServerDatasets();
                refreshLeaderboard();
            } else {
                loginStatusLabel.setText("Token inválido");
                loginStatusLabel.setForeground(Color.RED);
            }
        })).exceptionally(ex -> {
            SwingUtilities.invokeLater(() -> {
                loginStatusLabel.setText("Error: " + ex.getMessage());
                loginStatusLabel.setForeground(Color.RED);
            });
            return null;
        });
    }

    private void buildDashboardView() {
        adminTabs = new JTabbedPane();

        adminTabs.addTab("Cargar Datasets", buildDatasetsTab());
        adminTabs.setToolTipTextAt(0, "Subida e inspección de datasets CSV con configuración granular de columnas");

        adminTabs.addTab("Control de Campaña", buildCampaignTab());
        adminTabs.setToolTipTextAt(1, "Configuración y lanzamiento de campañas heurísticas y monitoreo del cluster");

        adminTabs.addTab("Leaderboard & Modelos .nnet", buildLeaderboardTab());
        adminTabs.setToolTipTextAt(2, "Ranking de redes neuronales entrenadas, prueba de inferencia y descarga .nnet");

        cardContainer.add(adminTabs, "DASHBOARD");
    }

    // --- Subpestaña Datasets ---
    private JPanel buildDatasetsTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel form = new JPanel(new GridLayout(4, 2, 8, 8));
        form.setBorder(new TitledBorder("1. Archivo y Configuración General"));

        form.add(new JLabel("Archivo CSV:"));
        JPanel fileChoosePanel = new JPanel(new BorderLayout(5, 0));
        JButton selectFileBtn = new JButton("Examinar CSV...");
        selectFileBtn.setToolTipText("Selecciona un archivo CSV desde tu computadora para analizar sus columnas y datos");
        selectedFileLabel = new JLabel("Ningún archivo seleccionado");
        fileChoosePanel.add(selectFileBtn, BorderLayout.WEST);
        fileChoosePanel.add(selectedFileLabel, BorderLayout.CENTER);
        form.add(fileChoosePanel);

        form.add(new JLabel("Estructura del Archivo:"));
        hasHeaderCheckbox = new JCheckBox("El archivo CSV contiene fila de encabezados", true);
        hasHeaderCheckbox.setToolTipText("Indica si la primera fila del archivo contiene nombres de columnas o si son directamente registros de datos");
        form.add(hasHeaderCheckbox);

        form.add(new JLabel("Nombre del Dataset:"));
        datasetNameField = new JTextField("Orquitas-Sensors-v1");
        datasetNameField.setToolTipText("Nombre identificador único del dataset dentro del servidor orquestador");
        form.add(datasetNameField);

        form.add(new JLabel("Tipo de Problema:"));
        taskTypeCombo = new JComboBox<>(TaskType.values());
        taskTypeCombo.setToolTipText("Tipo de problema: REGRESSION (predicción continua) o CLASSIFICATION (categorías/etiquetas)");
        form.add(taskTypeCombo);

        panel.add(form, BorderLayout.NORTH);

        // Centro: Split pane con Tabla de Configuración de Columnas y Vista Previa de Datos
        String[] colHeaders = {"Índice", "Nombre de Columna", "Rol", "Tipo de Normalización"};
        columnsModel = new DefaultTableModel(colHeaders, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column != 0; // El índice no se edita directamente
            }
        };
        columnsTable = new JTable(columnsModel);
        columnsTable.setRowHeight(24);
        columnsTable.setToolTipText("Configura el rol (INPUT, TARGET o IGNORE) y la normalización individual para cada columna");
        JScrollPane colsScroll = new JScrollPane(columnsTable);
        colsScroll.setBorder(new TitledBorder("2. Configuración Granular de Columnas (Auto-detectadas del CSV)"));

        previewModel = new DefaultTableModel();
        previewTable = new JTable(previewModel);
        previewTable.setRowHeight(20);
        previewTable.setToolTipText("Muestra las primeras filas leídas del archivo CSV para verificar el formato de los datos");
        JScrollPane previewScroll = new JScrollPane(previewTable);
        previewScroll.setBorder(new TitledBorder("3. Vista Previa de Datos (Primeras 10 filas)"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, colsScroll, previewScroll);
        splitPane.setDividerLocation(200);
        splitPane.setResizeWeight(0.5);
        panel.add(splitPane, BorderLayout.CENTER);

        // Pie: Normalización por defecto global y Botón de Subida
        JPanel southPanel = new JPanel(new BorderLayout(5, 5));
        JPanel extraForm = new JPanel(new GridLayout(2, 2, 5, 5));
        extraForm.add(new JLabel("Etiquetas de Clases (separadas por coma, si es multiclase):"));
        classLabelsField = new JTextField("LOBITO, MURO, OBSTACULO, SALIDA");
        classLabelsField.setToolTipText("Nombres de las categorías ordenadas por índice (ej: LOBITO, MURO, OBSTACULO, SALIDA) para clasificación");
        extraForm.add(classLabelsField);

        extraForm.add(new JLabel("Normalización global de respaldo:"));
        normCombo = new JComboBox<>(NormalizationType.values());
        normCombo.setToolTipText("Técnica de normalización por defecto aplicada a las columnas numéricas (MIN_MAX escala al rango [0, 1])");
        extraForm.add(normCombo);
        southPanel.add(extraForm, BorderLayout.NORTH);

        uploadButton = new JButton("Subir Dataset al Servidor Orquestador");
        uploadButton.setBackground(new Color(40, 140, 40));
        uploadButton.setForeground(Color.WHITE);
        uploadButton.setFont(uploadButton.getFont().deriveFont(Font.BOLD, 13f));
        uploadButton.setToolTipText("Envía y registra el dataset estructurado en el almacenamiento persistente del servidor");
        southPanel.add(uploadButton, BorderLayout.SOUTH);

        panel.add(southPanel, BorderLayout.SOUTH);

        selectFileBtn.addActionListener(e -> selectCsvFile());
        hasHeaderCheckbox.addActionListener(e -> {
            if (cachedCsvContent != null) {
                inspectAndPopulateCsv(cachedCsvContent);
            }
        });
        uploadButton.addActionListener(e -> uploadDataset());

        return panel;
    }

    private void selectCsvFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedCsvFile = chooser.getSelectedFile();
            try {
                cachedCsvContent = Files.readString(selectedCsvFile.toPath(), StandardCharsets.UTF_8);
                String baseName = selectedCsvFile.getName();
                if (baseName.toLowerCase().endsWith(".csv")) {
                    baseName = baseName.substring(0, baseName.length() - 4);
                }
                datasetNameField.setText(baseName);
                inspectAndPopulateCsv(cachedCsvContent);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error leyendo archivo CSV: " + ex.getMessage());
            }
        }
    }

    private void inspectAndPopulateCsv(String csvContent) {
        try {
            boolean hasHeader = hasHeaderCheckbox.isSelected();
            DatasetParser.CsvInspectionResult result = DatasetParser.inspectCsv(csvContent, hasHeader);

            selectedFileLabel.setText((selectedCsvFile != null ? selectedCsvFile.getName() : "CSV") +
                    " (" + result.getRowCount() + " filas, " + result.getColumnCount() + " columnas)");

            // 1. Llenar tabla de columnas auto-detectadas
            columnsModel.setRowCount(0);
            for (ColumnConfig cfg : result.getColumnConfigs()) {
                columnsModel.addRow(new Object[]{
                        cfg.getIndex(),
                        cfg.getName(),
                        cfg.getRole(),
                        cfg.getNormalization()
                });
            }

            // Asignar editores desplegables para Rol y Normalización
            TableColumn roleCol = columnsTable.getColumnModel().getColumn(2);
            roleCol.setCellEditor(new DefaultCellEditor(new JComboBox<>(ColumnRole.values())));

            TableColumn normCol = columnsTable.getColumnModel().getColumn(3);
            normCol.setCellEditor(new DefaultCellEditor(new JComboBox<>(NormalizationType.values())));

            // 2. Llenar tabla de vista previa
            previewModel.setRowCount(0);
            previewModel.setColumnCount(0);
            for (String h : result.getHeaders()) {
                previewModel.addColumn(h);
            }
            for (String[] row : result.getPreviewRows()) {
                previewModel.addRow(row);
            }

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error analizando CSV: " + ex.getMessage());
        }
    }

    private void uploadDataset() {
        if (cachedCsvContent == null || columnsModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this, "Selecciona primero un archivo CSV válido.");
            return;
        }

        if (columnsTable.isEditing()) {
            columnsTable.getCellEditor().stopCellEditing();
        }

        try {
            List<ColumnConfig> configs = new ArrayList<>();
            int inputCount = 0;
            int outputCount = 0;

            for (int i = 0; i < columnsModel.getRowCount(); i++) {
                int idx = ((Number) columnsModel.getValueAt(i, 0)).intValue();
                String name = String.valueOf(columnsModel.getValueAt(i, 1));
                ColumnRole role = (ColumnRole) columnsModel.getValueAt(i, 2);
                NormalizationType norm = (NormalizationType) columnsModel.getValueAt(i, 3);

                if (role == ColumnRole.INPUT) inputCount++;
                if (role == ColumnRole.OUTPUT) outputCount++;

                configs.add(new ColumnConfig(idx, name, role, norm));
            }

            if (inputCount == 0) {
                JOptionPane.showMessageDialog(this, "Debes marcar al menos una columna como ENTRADA (INPUT).");
                return;
            }
            if (outputCount == 0) {
                JOptionPane.showMessageDialog(this, "Debes marcar al menos una columna como SALIDA (OUTPUT).");
                return;
            }

            DatasetMetadata meta = new DatasetMetadata();
            meta.setId("ds-" + System.currentTimeMillis() % 10000);
            meta.setName(datasetNameField.getText().trim());
            meta.setFilename(selectedCsvFile != null ? selectedCsvFile.getName() : "dataset.csv");
            meta.setTaskType((TaskType) taskTypeCombo.getSelectedItem());
            meta.setHasHeader(hasHeaderCheckbox.isSelected());
            meta.setNormalization((NormalizationType) normCombo.getSelectedItem());
            meta.setColumnConfigs(configs);
            meta.setCsvContent(cachedCsvContent);

            if (meta.getTaskType() == TaskType.CLASSIFICATION) {
                String[] labels = classLabelsField.getText().split(",");
                List<String> list = Arrays.stream(labels).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                meta.setClassLabels(list);
            }

            int rowCount = cachedCsvContent.split("\n").length - (meta.isHasHeader() ? 1 : 0);
            meta.setNumRows(Math.max(1, rowCount));

            final int reportedInputs = inputCount;
            final int reportedOutputs = outputCount;

            connection.uploadDataset(meta).thenAccept(resp -> SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this, "¡Dataset subido con éxito al servidor con " +
                        reportedInputs + " entradas y " + reportedOutputs + " salidas!");
                refreshServerDatasets();
            })).exceptionally(ex -> {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage()));
                return null;
            });

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage());
        }
    }

    // --- Subpestaña Campaña ---
    private JPanel buildCampaignTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel configPanel = new JPanel(new GridLayout(10, 2, 8, 8));
        configPanel.setBorder(new TitledBorder("Parámetros de Búsqueda Heurística y Entrenamiento"));

        configPanel.add(new JLabel("Dataset Activo:"));
        datasetCombo = new JComboBox<>();
        datasetCombo.setToolTipText("Selecciona el dataset guardado en el servidor para entrenar las redes neuronales");
        configPanel.add(datasetCombo);

        configPanel.add(new JLabel("Estrategia Heurística:"));
        heuristicCombo = new JComboBox<>(HeuristicType.values());
        heuristicCombo.setToolTipText("Estrategia de búsqueda heurística: GUIDED_SEARCH (búsqueda informada), EVOLUTIONARY (genético), RANDOM_SEARCH");
        configPanel.add(heuristicCombo);

        configPanel.add(new JLabel("Capas Ocultas (Mín / Máx):"));
        JPanel layersBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        minLayersSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 3, 1));
        minLayersSpinner.setToolTipText("Número mínimo de capas ocultas a explorar en las arquitecturas neuronales");
        maxLayersSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 4, 1));
        maxLayersSpinner.setToolTipText("Número máximo de capas ocultas a explorar en las arquitecturas neuronales");
        layersBox.add(minLayersSpinner);
        layersBox.add(new JLabel("a"));
        layersBox.add(maxLayersSpinner);
        configPanel.add(layersBox);

        configPanel.add(new JLabel("Neuronas por Capa (Mín / Máx):"));
        JPanel neuronsBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        minNeuronsSpinner = new JSpinner(new SpinnerNumberModel(4, 2, 128, 1));
        minNeuronsSpinner.setToolTipText("Número mínimo de neuronas por capa oculta");
        maxNeuronsSpinner = new JSpinner(new SpinnerNumberModel(24, 2, 256, 1));
        maxNeuronsSpinner.setToolTipText("Número máximo de neuronas por capa oculta");
        neuronsBox.add(minNeuronsSpinner);
        neuronsBox.add(new JLabel("a"));
        neuronsBox.add(maxNeuronsSpinner);
        configPanel.add(neuronsBox);

        configPanel.add(new JLabel("Máximo de Épocas (Iteraciones):"));
        maxIterSpinner = new JSpinner(new SpinnerNumberModel(1000, 100, 50000, 100));
        maxIterSpinner.setToolTipText("Máximo de épocas de entrenamiento permitidas por cada tarea de red neuronal");
        configPanel.add(maxIterSpinner);

        configPanel.add(new JLabel("Early Stopping (Paciencia en Épocas):"));
        patienceSpinner = new JSpinner(new SpinnerNumberModel(80, 10, 2000, 10));
        patienceSpinner.setToolTipText("Early Stopping: épocas consecutivas toleradas sin mejora del error antes de detener la tarea");
        configPanel.add(patienceSpinner);

        configPanel.add(new JLabel("Error Objetivo (Target Error):"));
        targetErrorSpinner = new JSpinner(new SpinnerNumberModel(0.01, 0.0001, 0.5, 0.005));
        targetErrorSpinner.setToolTipText("Error cuadrático medio objetivo. Si la red lo alcanza, finaliza tempranamente con éxito");
        configPanel.add(targetErrorSpinner);

        configPanel.add(new JLabel("Data Augmentation (Ruido en Sensores):"));
        JPanel augBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        enableAugmentationCheckbox = new JCheckBox("Activar", false);
        enableAugmentationCheckbox.setToolTipText("Data Augmentation: sintetiza muestras agregando perturbación gaussiana a las entradas");
        augmentationFactorSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 5, 1));
        augmentationFactorSpinner.setToolTipText("Cantidad de réplicas sintéticas adicionales generadas por cada fila de entrenamiento");
        augmentationNoiseSpinner = new JSpinner(new SpinnerNumberModel(0.03, 0.005, 0.20, 0.005));
        augmentationNoiseSpinner.setToolTipText("Desviación estándar del ruido gaussiano añadido a los valores de los sensores");
        augBox.add(enableAugmentationCheckbox);
        augBox.add(new JLabel("Copias:"));
        augBox.add(augmentationFactorSpinner);
        augBox.add(new JLabel("Ruido:"));
        augBox.add(augmentationNoiseSpinner);
        configPanel.add(augBox);

        configPanel.add(new JLabel("Total de Tareas a Explorar:"));
        maxTasksSpinner = new JSpinner(new SpinnerNumberModel(30, 5, 500, 5));
        maxTasksSpinner.setToolTipText("Cantidad total de configuraciones de redes neuronales a evaluar en esta campaña");
        configPanel.add(maxTasksSpinner);

        // Botones de Acción
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        startCampaignBtn = new JButton("Iniciar Campaña");
        startCampaignBtn.setBackground(new Color(40, 140, 40));
        startCampaignBtn.setForeground(Color.WHITE);
        startCampaignBtn.setToolTipText("Inicia la campaña heurística en el servidor para despachar tareas a todos los workers conectados");

        pauseCampaignBtn = new JButton("Pausar");
        pauseCampaignBtn.setToolTipText("Pausa temporalmente la asignación de nuevas tareas de entrenamiento");

        stopCampaignBtn = new JButton("Detener");
        stopCampaignBtn.setToolTipText("Detiene la campaña actual y cancela las tareas pendientes en la cola");

        actionPanel.add(startCampaignBtn);
        actionPanel.add(pauseCampaignBtn);
        actionPanel.add(stopCampaignBtn);
        configPanel.add(new JLabel("Acciones:"));
        configPanel.add(actionPanel);

        panel.add(configPanel, BorderLayout.NORTH);

        // Estado en tiempo real del Cluster
        JPanel statusBox = new JPanel(new GridLayout(2, 1, 5, 5));
        statusBox.setBorder(new TitledBorder("Estado del Cluster y Servidor"));
        campaignStatusLabel = new JLabel("Campaña: Ninguna activa", SwingConstants.CENTER);
        workersStatusLabel = new JLabel("Workers: 0 conectados | 0 slots libres", SwingConstants.CENTER);
        campaignStatusLabel.setFont(campaignStatusLabel.getFont().deriveFont(Font.BOLD, 13f));
        workersStatusLabel.setFont(workersStatusLabel.getFont().deriveFont(Font.PLAIN, 12f));

        statusBox.add(campaignStatusLabel);
        statusBox.add(workersStatusLabel);
        panel.add(statusBox, BorderLayout.CENTER);

        startCampaignBtn.addActionListener(e -> startCampaign());
        pauseCampaignBtn.addActionListener(e -> connection.pauseCampaign());
        stopCampaignBtn.addActionListener(e -> connection.stopCampaign());

        return panel;
    }

    private void startCampaign() {
        String dsId = (String) datasetCombo.getSelectedItem();
        if (dsId == null || dsId.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Primero sube o selecciona un dataset.");
            return;
        }

        CampaignConfig cfg = new CampaignConfig();
        cfg.setName("Campaña-" + System.currentTimeMillis() % 1000);
        cfg.setDatasetId(dsId);
        cfg.setHeuristicType((HeuristicType) heuristicCombo.getSelectedItem());
        cfg.setMinHiddenLayers((Integer) minLayersSpinner.getValue());
        cfg.setMaxHiddenLayers((Integer) maxLayersSpinner.getValue());
        cfg.setMinNeuronsPerLayer((Integer) minNeuronsSpinner.getValue());
        cfg.setMaxNeuronsPerLayer((Integer) maxNeuronsSpinner.getValue());
        cfg.setMaxIterations((Integer) maxIterSpinner.getValue());
        cfg.setPatience(((Number) patienceSpinner.getValue()).intValue());
        cfg.setTargetError(((Number) targetErrorSpinner.getValue()).doubleValue());
        cfg.setEnableAugmentation(enableAugmentationCheckbox.isSelected());
        cfg.setAugmentationFactor(((Number) augmentationFactorSpinner.getValue()).intValue());
        cfg.setAugmentationNoise(((Number) augmentationNoiseSpinner.getValue()).doubleValue());
        cfg.setMaxTotalTasks((Integer) maxTasksSpinner.getValue());

        connection.startCampaign(cfg).thenAccept(r -> SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, "¡Campaña iniciada! Las tareas se están despachando a los workers.");
            refreshStatus();
        })).exceptionally(ex -> {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage()));
            return null;
        });
    }

    private void refreshServerDatasets() {
        connection.getStatus().thenAccept(json -> SwingUtilities.invokeLater(() -> {
            Map<String, Object> map = JsonUtil.fromJson(json, Map.class);
            if (map != null && map.containsKey("datasets")) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) map.get("datasets");
                datasetCombo.removeAllItems();
                for (Map<String, Object> d : list) {
                    datasetCombo.addItem((String) d.get("id"));
                }
            }
        }));
    }

    private void refreshStatus() {
        connection.getStatus().thenAccept(json -> SwingUtilities.invokeLater(() -> {
            Map<String, Object> map = JsonUtil.fromJson(json, Map.class);
            if (map != null) {
                int workers = ((Number) map.getOrDefault("activeWorkers", 0)).intValue();
                int totalSlots = ((Number) map.getOrDefault("totalSlots", 0)).intValue();
                int busySlots = ((Number) map.getOrDefault("busySlots", 0)).intValue();
                int pending = ((Number) map.getOrDefault("pendingTasks", 0)).intValue();
                int running = ((Number) map.getOrDefault("runningTasks", 0)).intValue();
                int completed = ((Number) map.getOrDefault("completedTasks", 0)).intValue();

                workersStatusLabel.setText(String.format("Workers: %d conectados | Slots: %d en uso / %d totales",
                        workers, busySlots, totalSlots));
                campaignStatusLabel.setText(String.format("Tareas: %d pendientes | %d en progreso | %d completadas",
                        pending, running, completed));
            }
        }));
    }

    // --- Subpestaña Leaderboard ---
    private JPanel buildLeaderboardTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        String[] cols = {
                "ID", "Topología", "Transfer", "Accuracy (%)", "RMSE", "MSE",
                "MAE", "R2", "MAPE (%)", "MaxError", "P90", "Tiempo (s)", "Iter", "ErrorFinal", "Worker"
        };

        leaderboardModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        leaderboardTable = new JTable(leaderboardModel);
        leaderboardTable.setAutoCreateRowSorter(true);
        leaderboardTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        leaderboardTable.setToolTipText("Selecciona una fila para probar inferencia en vivo o descargar el archivo .nnet");

        String[] columnToolTips = {
                "Identificador único de la tarea",
                "Topología de capas neuronales (Entradas-Ocultas-Salidas)",
                "Función de activación/transferencia en capas ocultas",
                "Porcentaje de acierto en clasificación sobre el conjunto de test",
                "Raíz del Error Cuadrático Medio en conjunto de prueba",
                "Error Cuadrático Medio en conjunto de prueba",
                "Error Absoluto Medio promedio en conjunto de prueba",
                "Coeficiente de determinación R² (calidad de ajuste del modelo)",
                "Error Porcentual Absoluto Medio (%)",
                "Error máximo absoluto registrado en una sola muestra",
                "Percentil 90 del error absoluto",
                "Tiempo total de entrenamiento en segundos",
                "Épocas ejecutadas hasta convergencia o early stopping",
                "Error de entrenamiento alcanzado al finalizar",
                "Nombre del nodo (Worker) que entrenó este modelo"
        };
        javax.swing.table.JTableHeader header = new javax.swing.table.JTableHeader(leaderboardTable.getColumnModel()) {
            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
                int col = columnAtPoint(e.getPoint());
                int modelCol = getTable().convertColumnIndexToModel(col);
                if (modelCol >= 0 && modelCol < columnToolTips.length) {
                    return columnToolTips[modelCol];
                }
                return super.getToolTipText(e);
            }
        };
        leaderboardTable.setTableHeader(header);

        JScrollPane scroll = new JScrollPane(leaderboardTable);
        panel.add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        refreshLbButton = new JButton("Actualizar Tabla");
        refreshLbButton.setToolTipText("Consulta al servidor el estado más reciente del ranking de modelos evaluados");

        JButton testInferenceBtn = new JButton("Probar Inferencia en Vivo");
        testInferenceBtn.setToolTipText("Abre una ventana para ingresar entradas manuales y probar la predicción de la red en tiempo real");

        downloadNnetButton = new JButton("Descargar .nnet");
        downloadNnetButton.setBackground(new Color(60, 110, 180));
        downloadNnetButton.setForeground(Color.WHITE);
        downloadNnetButton.setToolTipText("Descarga el archivo binario compilado .nnet de la red neuronal seleccionada");

        bottom.add(refreshLbButton);
        bottom.add(testInferenceBtn);
        bottom.add(downloadNnetButton);
        panel.add(bottom, BorderLayout.SOUTH);

        refreshLbButton.addActionListener(e -> refreshLeaderboard());
        testInferenceBtn.addActionListener(e -> openInferenceTestDialog());
        downloadNnetButton.addActionListener(e -> downloadSelectedModel());

        return panel;
    }

    private void openInferenceTestDialog() {
        int row = leaderboardTable.getSelectedRow();
        if (row < 0 || row >= currentLeaderboard.size()) {
            JOptionPane.showMessageDialog(this, "Selecciona un modelo del leaderboard primero.");
            return;
        }

        int modelRow = leaderboardTable.convertRowIndexToModel(row);
        TaskResult selected = currentLeaderboard.get(modelRow);

        connection.downloadModel(selected.getCampaignId(), selected.getTaskId()).thenAccept(b64 -> SwingUtilities.invokeLater(() -> {
            try {
                org.neuroph.core.NeuralNetwork<?> net = com.neuroph.train.common.util.NetworkSerializer.fromBase64(b64);
                int inputCount = net.getInputsCount();
                int outputCount = net.getOutputsCount();

                JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Prueba de Inferencia - " + selected.getTaskId(), true);
                dialog.setLayout(new BorderLayout(10, 10));
                dialog.setSize(450, 380);
                dialog.setLocationRelativeTo(this);

                JPanel inputsPanel = new JPanel(new GridLayout(inputCount, 2, 5, 5));
                inputsPanel.setBorder(new TitledBorder("Valores de Entrada (Sensores)"));
                JTextField[] inFields = new JTextField[inputCount];
                for (int i = 0; i < inputCount; i++) {
                    inputsPanel.add(new JLabel("Entrada " + i + ":"));
                    inFields[i] = new JTextField("0.5");
                    inputsPanel.add(inFields[i]);
                }

                JPanel resultPanel = new JPanel(new BorderLayout());
                resultPanel.setBorder(new TitledBorder("Predicción de la Red"));
                JTextArea resArea = new JTextArea(5, 30);
                resArea.setEditable(false);
                resultPanel.add(new JScrollPane(resArea), BorderLayout.CENTER);

                JButton calcBtn = new JButton("▶ Calcular Salida");
                calcBtn.setBackground(new Color(40, 140, 40));
                calcBtn.setForeground(Color.WHITE);
                calcBtn.addActionListener(ev -> {
                    try {
                        double[] in = new double[inputCount];
                        for (int i = 0; i < inputCount; i++) {
                            in[i] = Double.parseDouble(inFields[i].getText().trim());
                        }
                        net.setInput(in);
                        net.calculate();
                        double[] out = net.getOutput();

                        StringBuilder sb = new StringBuilder();
                        sb.append("Salidas crudas: ").append(Arrays.toString(out)).append("\n");

                        List<String> labels = selected.getMetrics() != null ? selected.getMetrics().getClassLabels() : null;
                        if (labels != null && labels.size() == out.length) {
                            int bestIdx = 0;
                            double maxV = out[0];
                            for (int k = 1; k < out.length; k++) {
                                if (out[k] > maxV) {
                                    maxV = out[k];
                                    bestIdx = k;
                                }
                            }
                            sb.append(String.format("Clase Predicha: %s (Activación: %.2f%%)\n",
                                    labels.get(bestIdx), maxV * 100.0));
                        }
                        resArea.setText(sb.toString());
                    } catch (Exception ex) {
                        resArea.setText("Error en cálculo: " + ex.getMessage());
                    }
                });

                dialog.add(inputsPanel, BorderLayout.NORTH);
                dialog.add(resultPanel, BorderLayout.CENTER);
                dialog.add(calcBtn, BorderLayout.SOUTH);
                dialog.setVisible(true);

            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error cargando red para inferencia: " + ex.getMessage());
            }
        })).exceptionally(ex -> {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage()));
            return null;
        });
    }

    private void refreshLeaderboard() {
        connection.getLeaderboard(null).thenAccept(json -> SwingUtilities.invokeLater(() -> {
            TaskResult[] results = JsonUtil.fromJson(json, TaskResult[].class);
            leaderboardModel.setRowCount(0);
            currentLeaderboard.clear();

            if (results != null) {
                for (TaskResult r : results) {
                    currentLeaderboard.add(r);
                    EvaluationMetrics m = r.getMetrics();
                    leaderboardModel.addRow(new Object[]{
                            r.getTaskId(),
                            r.getNetworkConfig() != null ? r.getNetworkConfig().getTopologySummary() : "-",
                            r.getNetworkConfig() != null ? r.getNetworkConfig().getTransferFunction() : "-",
                            m != null ? String.format("%.2f", m.getAccuracy()) : "-",
                            m != null ? String.format("%.4f", m.getRmse()) : "-",
                            m != null ? String.format("%.4f", m.getMse()) : "-",
                            m != null ? String.format("%.4f", m.getMae()) : "-",
                            m != null ? String.format("%.4f", m.getR2()) : "-",
                            m != null ? String.format("%.2f", m.getMape()) : "-",
                            m != null ? String.format("%.4f", m.getMaxError()) : "-",
                            m != null ? String.format("%.4f", m.getPercentil90()) : "-",
                            m != null ? String.format("%.2f", m.getTiempoSeg()) : "-",
                            m != null ? m.getIteraciones() : "-",
                            m != null ? String.format("%.5f", m.getErrorFinal()) : "-",
                            r.getWorkerName()
                    });
                }
            }
            refreshStatus();
        }));
    }

    private void downloadSelectedModel() {
        int row = leaderboardTable.getSelectedRow();
        if (row < 0 || row >= currentLeaderboard.size()) {
            JOptionPane.showMessageDialog(this, "Selecciona una fila del leaderboard primero.");
            return;
        }

        int modelRow = leaderboardTable.convertRowIndexToModel(row);
        TaskResult selected = currentLeaderboard.get(modelRow);

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("orquita_model_" + selected.getTaskId() + ".nnet"));

        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File targetFile = chooser.getSelectedFile();
            connection.downloadModel(selected.getCampaignId(), selected.getTaskId()).thenAccept(b64 -> SwingUtilities.invokeLater(() -> {
                try {
                    byte[] bytes = Base64.getDecoder().decode(b64);
                    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                        fos.write(bytes);
                    }
                    JOptionPane.showMessageDialog(this, "¡Archivo guardado con éxito en:\n" + targetFile.getAbsolutePath());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Error guardando archivo: " + ex.getMessage());
                }
            })).exceptionally(ex -> {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error descargando: " + ex.getMessage()));
                return null;
            });
        }
    }
}
