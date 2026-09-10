package com.neuroph.train.client.gui;

import com.neuroph.train.client.network.ServerConnection;
import com.neuroph.train.common.model.CampaignConfig;
import com.neuroph.train.common.model.ColumnConfig;
import com.neuroph.train.common.model.ColumnRole;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.EvaluationMetrics;
import com.neuroph.train.common.model.HeuristicType;
import com.neuroph.train.common.model.NormalizationType;
import com.neuroph.train.common.model.Project;
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
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Panel de Administracion protegido por token:
 * - Gestion multi-proyecto y agrupacion de datasets
 * - Subida de datasets con normalizacion granular y deteccion automatica
 * - Control de campanas de entrenamiento concurrentes (Fair-Share Round-Robin)
 * - Leaderboard por dataset o proyecto con descarga de modelos .nnet
 * - Vista comparativa de rendimiento entre datasets del mismo proyecto
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

    // Cache local de proyectos y datasets
    private final List<Project> cachedProjects = new ArrayList<>();
    private final List<DatasetMetadata> cachedDatasets = new ArrayList<>();
    private final List<CampaignConfig> cachedActiveCampaigns = new ArrayList<>();

    // Subpestaña Datasets
    private JComboBox<Project> uploadProjectCombo;
    private JTextField datasetNameField;
    private JComboBox<TaskType> taskTypeCombo;
    private JCheckBox hasHeaderCheckbox;
    private JTextField classLabelsField;
    private JComboBox<NormalizationType> normCombo;
    private File selectedCsvFile;
    private String cachedCsvContent;
    private JTable columnsTable;
    private DefaultTableModel columnsModel;
    private JTable previewTable;
    private DefaultTableModel previewModel;
    private JLabel selectedFileLabel;
    private JButton uploadButton;

    // Subpestaña Campaña
    private JComboBox<Project> campaignProjectCombo;
    private JComboBox<DatasetMetadata> campaignDatasetCombo;
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
    private JTable activeCampaignsTable;
    private DefaultTableModel activeCampaignsModel;
    private JButton pauseCampaignBtn;
    private JButton resumeCampaignBtn;
    private JButton stopCampaignBtn;
    private JLabel campaignStatusLabel;
    private JLabel workersStatusLabel;

    // Subpestaña Leaderboard
    private JComboBox<Project> lbProjectCombo;
    private JComboBox<DatasetMetadata> lbDatasetCombo;
    private JTable leaderboardTable;
    private DefaultTableModel leaderboardModel;
    private JButton refreshLbButton;
    private JButton downloadNnetButton;
    private final List<TaskResult> currentLeaderboard = new ArrayList<>();

    // Subpestaña Comparativa de Datasets
    private JComboBox<Project> compProjectCombo;
    private JTable bestPerDatasetTable;
    private DefaultTableModel bestPerDatasetModel;
    private final List<TaskResult> currentBestPerDataset = new ArrayList<>();
    private JTable unifiedProjectTable;
    private DefaultTableModel unifiedProjectModel;
    private final List<TaskResult> currentUnifiedProject = new ArrayList<>();

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
        box.setBorder(new TitledBorder("Autenticacion de Administrador (Dokploy / VPS)"));
        box.setPreferredSize(new Dimension(380, 200));

        box.add(new JLabel("Introduce el ADMIN_TOKEN configurado en el servidor:", SwingConstants.CENTER));
        tokenField = new JPasswordField(15);
        tokenField.setToolTipText("Introduce la clave secreta ADMIN_TOKEN configurada en el servidor");
        box.add(tokenField);

        loginButton = new JButton("Acceder al Panel de Control");
        loginButton.setBackground(new Color(60, 100, 180));
        loginButton.setForeground(Color.WHITE);
        loginButton.setToolTipText("Valida las credenciales de administrador para desbloquear el panel de control");
        box.add(loginButton);

        loginStatusLabel = new JLabel("Requiere conexion activa previa al servidor", SwingConstants.CENTER);
        loginStatusLabel.setForeground(Color.GRAY);
        box.add(loginStatusLabel);

        loginButton.addActionListener(e -> attemptLogin());

        loginPanel.add(box);
        cardContainer.add(loginPanel, "LOGIN");
    }

    private void attemptLogin() {
        if (!connection.isConnected()) {
            loginStatusLabel.setText("Primero conecta el cliente en la pestana Worker");
            loginStatusLabel.setForeground(Color.RED);
            return;
        }

        String token = new String(tokenField.getPassword()).trim();
        if (token.isEmpty()) {
            loginStatusLabel.setText("El token no puede estar vacio");
            loginStatusLabel.setForeground(Color.RED);
            return;
        }

        loginStatusLabel.setText("Validando token con el servidor...");
        loginStatusLabel.setForeground(Color.ORANGE);

        connection.authenticateAdmin(token).thenAccept(ok -> SwingUtilities.invokeLater(() -> {
            if (ok) {
                cardLayout.show(cardContainer, "DASHBOARD");
                refreshServerProjectsAndDatasets(this::refreshLeaderboard);
            } else {
                loginStatusLabel.setText("Token invalido");
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
        adminTabs.setToolTipTextAt(0, "Subida e inspeccion de datasets CSV asociados a proyectos");

        adminTabs.addTab("Control de Campanas", buildCampaignTab());
        adminTabs.setToolTipTextAt(1, "Configuracion, inicio y control concurrente de campanas de entrenamiento");

        adminTabs.addTab("Leaderboard & Modelos .nnet", buildLeaderboardTab());
        adminTabs.setToolTipTextAt(2, "Ranking de modelos neuronales por proyecto o dataset con descarga .nnet");

        adminTabs.addTab("Comparativa de Datasets", buildComparisonTab());
        adminTabs.setToolTipTextAt(3, "Comparativa de rendimiento entre datasets del mismo proyecto");

        cardContainer.add(adminTabs, "DASHBOARD");
    }

    // --- Subpestaña 1: Cargar Datasets ---
    private JPanel buildDatasetsTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel form = new JPanel(new GridLayout(5, 2, 8, 8));
        form.setBorder(new TitledBorder("1. Proyecto y Archivo"));

        form.add(new JLabel("Proyecto Asociado:"));
        JPanel projectSelectPanel = new JPanel(new BorderLayout(5, 0));
        uploadProjectCombo = new JComboBox<>();
        uploadProjectCombo.setToolTipText("Selecciona el proyecto al que pertenecera este dataset");
        JButton newProjectBtn = new JButton("+ Nuevo Proyecto...");
        newProjectBtn.setToolTipText("Crea un nuevo proyecto con solo un nombre para agrupar datasets");
        newProjectBtn.addActionListener(e -> promptCreateProject());
        projectSelectPanel.add(uploadProjectCombo, BorderLayout.CENTER);
        projectSelectPanel.add(newProjectBtn, BorderLayout.EAST);
        form.add(projectSelectPanel);

        form.add(new JLabel("Archivo CSV:"));
        JPanel fileChoosePanel = new JPanel(new BorderLayout(5, 0));
        JButton selectFileBtn = new JButton("Examinar CSV...");
        selectFileBtn.setToolTipText("Selecciona un archivo CSV desde tu computadora para analizar sus columnas y datos");
        selectedFileLabel = new JLabel("Ningun archivo seleccionado");
        fileChoosePanel.add(selectFileBtn, BorderLayout.WEST);
        fileChoosePanel.add(selectedFileLabel, BorderLayout.CENTER);
        form.add(fileChoosePanel);

        form.add(new JLabel("Estructura del Archivo:"));
        hasHeaderCheckbox = new JCheckBox("El archivo CSV contiene fila de encabezados", true);
        hasHeaderCheckbox.setToolTipText("Indica si la primera fila contiene nombres de columnas o son registros de datos");
        form.add(hasHeaderCheckbox);

        form.add(new JLabel("Nombre del Dataset:"));
        datasetNameField = new JTextField("Orquitas-Sensors-v1");
        datasetNameField.setToolTipText("Nombre identificador unico del dataset dentro del proyecto");
        form.add(datasetNameField);

        form.add(new JLabel("Tipo de Problema:"));
        taskTypeCombo = new JComboBox<>(TaskType.values());
        taskTypeCombo.setToolTipText("Tipo de problema: REGRESSION o CLASSIFICATION");
        form.add(taskTypeCombo);

        panel.add(form, BorderLayout.NORTH);

        // Centro: Columnas auto-detectadas y vista previa
        String[] colHeaders = {"Indice", "Nombre de Columna", "Rol", "Tipo de Normalizacion"};
        columnsModel = new DefaultTableModel(colHeaders, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column != 0;
            }
        };
        columnsTable = new JTable(columnsModel);
        columnsTable.setRowHeight(24);
        columnsTable.setToolTipText("Configura el rol (INPUT, TARGET o IGNORE) y la normalizacion para cada columna");
        JScrollPane colsScroll = new JScrollPane(columnsTable);
        colsScroll.setBorder(new TitledBorder("2. Configuracion Granular de Columnas (Auto-detectadas del CSV)"));

        previewModel = new DefaultTableModel();
        previewTable = new JTable(previewModel);
        previewTable.setRowHeight(20);
        previewTable.setToolTipText("Muestra las primeras filas leidas del archivo CSV");
        JScrollPane previewScroll = new JScrollPane(previewTable);
        previewScroll.setBorder(new TitledBorder("3. Vista Previa de Datos (Primeras 10 filas)"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, colsScroll, previewScroll);
        splitPane.setDividerLocation(180);
        splitPane.setResizeWeight(0.5);
        panel.add(splitPane, BorderLayout.CENTER);

        // Pie: Normalización por defecto global y Botón de Subida
        JPanel southPanel = new JPanel(new BorderLayout(5, 5));
        JPanel extraForm = new JPanel(new GridLayout(2, 2, 5, 5));
        extraForm.add(new JLabel("Etiquetas de Clases (separadas por coma, si es multiclase):"));
        classLabelsField = new JTextField("LOBITO, MURO, OBSTACULO, SALIDA");
        classLabelsField.setToolTipText("Nombres de las categorias ordenadas por indice para clasificacion");
        extraForm.add(classLabelsField);

        extraForm.add(new JLabel("Normalizacion global de respaldo:"));
        normCombo = new JComboBox<>(NormalizationType.values());
        normCombo.setToolTipText("Tecnica de normalizacion por defecto aplicada a las columnas numericas");
        extraForm.add(normCombo);
        southPanel.add(extraForm, BorderLayout.NORTH);

        uploadButton = new JButton("Subir Dataset al Servidor Orquestador");
        uploadButton.setBackground(new Color(40, 140, 40));
        uploadButton.setForeground(Color.WHITE);
        uploadButton.setFont(uploadButton.getFont().deriveFont(Font.BOLD, 13f));
        uploadButton.setToolTipText("Envia y registra el dataset estructurado en el almacenamiento persistente del servidor");
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

    private void promptCreateProject() {
        String name = JOptionPane.showInputDialog(this,
                "Introduce el nombre del nuevo proyecto:",
                "Crear Proyecto",
                JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.trim().isEmpty()) {
            final String projName = name.trim();
            connection.createProject(projName).thenAccept(json -> SwingUtilities.invokeLater(() -> {
                refreshServerProjectsAndDatasets(() -> {
                    for (int i = 0; i < uploadProjectCombo.getItemCount(); i++) {
                        Project p = uploadProjectCombo.getItemAt(i);
                        if (p != null && p.getName().equalsIgnoreCase(projName)) {
                            uploadProjectCombo.setSelectedIndex(i);
                            break;
                        }
                    }
                    JOptionPane.showMessageDialog(this, "Proyecto '" + projName + "' creado exitosamente.");
                });
            })).exceptionally(ex -> {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error creando proyecto: " + ex.getMessage()));
                return null;
            });
        }
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

            columnsModel.setRowCount(0);
            for (ColumnConfig cfg : result.getColumnConfigs()) {
                columnsModel.addRow(new Object[]{
                        cfg.getIndex(),
                        cfg.getName(),
                        cfg.getRole(),
                        cfg.getNormalization()
                });
            }

            TableColumn roleCol = columnsTable.getColumnModel().getColumn(2);
            roleCol.setCellEditor(new DefaultCellEditor(new JComboBox<>(ColumnRole.values())));

            TableColumn normCol = columnsTable.getColumnModel().getColumn(3);
            normCol.setCellEditor(new DefaultCellEditor(new JComboBox<>(NormalizationType.values())));

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
            JOptionPane.showMessageDialog(this, "Selecciona primero un archivo CSV valido.");
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

            Project selectedProj = (Project) uploadProjectCombo.getSelectedItem();
            String projId = selectedProj != null ? selectedProj.getId() : "default-project";
            String projName = selectedProj != null ? selectedProj.getName() : "Proyecto Principal";

            DatasetMetadata meta = new DatasetMetadata();
            meta.setId("ds-" + System.currentTimeMillis() % 10000);
            meta.setName(datasetNameField.getText().trim());
            meta.setProjectId(projId);
            meta.setProjectName(projName);
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
                JOptionPane.showMessageDialog(this, "Dataset subido con exito al servidor en el proyecto '" +
                        projName + "' con " + reportedInputs + " entradas y " + reportedOutputs + " salidas.");
                refreshServerProjectsAndDatasets(null);
            })).exceptionally(ex -> {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage()));
                return null;
            });

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage());
        }
    }

    // --- Subpestaña 2: Control de Campañas ---
    private JPanel buildCampaignTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel configPanel = new JPanel(new GridLayout(11, 2, 8, 8));
        configPanel.setBorder(new TitledBorder("1. Nueva Campana de Entrenamiento"));

        configPanel.add(new JLabel("Proyecto:"));
        campaignProjectCombo = new JComboBox<>();
        campaignProjectCombo.setToolTipText("Filtra los datasets disponibles por proyecto seleccionado");
        campaignProjectCombo.addActionListener(e -> updateCampaignDatasetsDropdown());
        configPanel.add(campaignProjectCombo);

        configPanel.add(new JLabel("Dataset:"));
        campaignDatasetCombo = new JComboBox<>();
        campaignDatasetCombo.setToolTipText("Selecciona el dataset sobre el que se ejecutara esta campana de entrenamiento");
        configPanel.add(campaignDatasetCombo);

        configPanel.add(new JLabel("Estrategia Heuristica:"));
        heuristicCombo = new JComboBox<>(HeuristicType.values());
        heuristicCombo.setToolTipText("Estrategia de busqueda heuristica: GUIDED_SEARCH, EVOLUTIONARY, RANDOM_SEARCH");
        configPanel.add(heuristicCombo);

        configPanel.add(new JLabel("Capas Ocultas (Min / Max):"));
        JPanel layersBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        minLayersSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 3, 1));
        minLayersSpinner.setToolTipText("Numero minimo de capas ocultas a explorar");
        maxLayersSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 4, 1));
        maxLayersSpinner.setToolTipText("Numero maximo de capas ocultas a explorar");
        layersBox.add(minLayersSpinner);
        layersBox.add(new JLabel("a"));
        layersBox.add(maxLayersSpinner);
        configPanel.add(layersBox);

        configPanel.add(new JLabel("Neuronas por Capa (Min / Max):"));
        JPanel neuronsBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        minNeuronsSpinner = new JSpinner(new SpinnerNumberModel(4, 2, 128, 1));
        minNeuronsSpinner.setToolTipText("Numero minimo de neuronas por capa oculta");
        maxNeuronsSpinner = new JSpinner(new SpinnerNumberModel(24, 2, 256, 1));
        maxNeuronsSpinner.setToolTipText("Numero maximo de neuronas por capa oculta");
        neuronsBox.add(minNeuronsSpinner);
        neuronsBox.add(new JLabel("a"));
        neuronsBox.add(maxNeuronsSpinner);
        configPanel.add(neuronsBox);

        configPanel.add(new JLabel("Maximo de Epocas (Iteraciones):"));
        maxIterSpinner = new JSpinner(new SpinnerNumberModel(1000, 100, 50000, 100));
        maxIterSpinner.setToolTipText("Maximo de epocas permitidas por cada red neuronal evaluada");
        configPanel.add(maxIterSpinner);

        configPanel.add(new JLabel("Early Stopping (Paciencia en Epocas):"));
        patienceSpinner = new JSpinner(new SpinnerNumberModel(80, 10, 2000, 10));
        patienceSpinner.setToolTipText("Early Stopping: epocas consecutivas sin mejora del error antes de detener la tarea");
        configPanel.add(patienceSpinner);

        configPanel.add(new JLabel("Error Objetivo (Target Error):"));
        targetErrorSpinner = new JSpinner(new SpinnerNumberModel(0.01, 0.0001, 0.5, 0.005));
        targetErrorSpinner.setToolTipText("Error cuadratico medio objetivo. Si la red lo alcanza, finaliza con exito");
        configPanel.add(targetErrorSpinner);

        configPanel.add(new JLabel("Data Augmentation (Ruido en Sensores):"));
        JPanel augBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        enableAugmentationCheckbox = new JCheckBox("Activar", false);
        enableAugmentationCheckbox.setToolTipText("Data Augmentation: sintetiza muestras agregando perturbacion gaussiana");
        augmentationFactorSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 5, 1));
        augmentationFactorSpinner.setToolTipText("Cantidad de replicas sinteticas generadas por fila");
        augmentationNoiseSpinner = new JSpinner(new SpinnerNumberModel(0.03, 0.005, 0.20, 0.005));
        augmentationNoiseSpinner.setToolTipText("Desviacion estandar del ruido gaussiano anadido");
        augBox.add(enableAugmentationCheckbox);
        augBox.add(new JLabel("Copias:"));
        augBox.add(augmentationFactorSpinner);
        augBox.add(new JLabel("Ruido:"));
        augBox.add(augmentationNoiseSpinner);
        configPanel.add(augBox);

        configPanel.add(new JLabel("Total de Tareas a Explorar:"));
        maxTasksSpinner = new JSpinner(new SpinnerNumberModel(30, 5, 500, 5));
        maxTasksSpinner.setToolTipText("Cantidad total de configuraciones de redes neuronales a evaluar");
        configPanel.add(maxTasksSpinner);

        startCampaignBtn = new JButton("Iniciar Campana");
        startCampaignBtn.setBackground(new Color(40, 140, 40));
        startCampaignBtn.setForeground(Color.WHITE);
        startCampaignBtn.setToolTipText("Lanza esta campana de entrenamiento independiente en el cluster");
        startCampaignBtn.addActionListener(e -> startCampaign());

        configPanel.add(new JLabel("Accion:"));
        configPanel.add(startCampaignBtn);

        panel.add(configPanel, BorderLayout.NORTH);

        // Centro: Tabla de Campañas Activas concurrentes y Monitoreo del Cluster
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
        centerPanel.setBorder(new TitledBorder("2. Campanas Activas en el Cluster (Fair-Share Round-Robin)"));

        String[] campHeaders = {"ID Campana", "Nombre", "Proyecto", "Dataset", "Estrategia", "Estado", "Paciencia", "Error Obj."};
        activeCampaignsModel = new DefaultTableModel(campHeaders, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        activeCampaignsTable = new JTable(activeCampaignsModel);
        activeCampaignsTable.setRowHeight(22);
        activeCampaignsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        activeCampaignsTable.setToolTipText("Selecciona una campana activa para pausarla, reanudarla o detenerla individualmente");
        centerPanel.add(new JScrollPane(activeCampaignsTable), BorderLayout.CENTER);

        JPanel campActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        pauseCampaignBtn = new JButton("Pausar Seleccionada");
        pauseCampaignBtn.setToolTipText("Pausa temporalmente la asignacion de nuevas tareas para la campana seleccionada");
        resumeCampaignBtn = new JButton("Reanudar Seleccionada");
        resumeCampaignBtn.setToolTipText("Reanuda el despacho de tareas para la campana seleccionada");
        stopCampaignBtn = new JButton("Detener Seleccionada");
        stopCampaignBtn.setToolTipText("Detiene definitivamente la campana seleccionada y cancela sus tareas en cola");
        JButton refreshStatusBtn = new JButton("Actualizar");
        refreshStatusBtn.setToolTipText("Consulta el estado mas reciente de las campanas y workers en el servidor");

        campActions.add(pauseCampaignBtn);
        campActions.add(resumeCampaignBtn);
        campActions.add(stopCampaignBtn);
        campActions.add(refreshStatusBtn);
        centerPanel.add(campActions, BorderLayout.SOUTH);

        pauseCampaignBtn.addActionListener(e -> pauseSelectedCampaign());
        resumeCampaignBtn.addActionListener(e -> resumeSelectedCampaign());
        stopCampaignBtn.addActionListener(e -> stopSelectedCampaign());
        refreshStatusBtn.addActionListener(e -> refreshStatus());

        panel.add(centerPanel, BorderLayout.CENTER);

        // Pie: Estado del Cluster
        JPanel statusBox = new JPanel(new GridLayout(2, 1, 5, 5));
        statusBox.setBorder(new TitledBorder("Estado del Cluster de Workers"));
        campaignStatusLabel = new JLabel("Tareas: 0 pendientes | 0 en progreso | 0 completadas", SwingConstants.CENTER);
        workersStatusLabel = new JLabel("Workers: 0 conectados | 0 slots libres", SwingConstants.CENTER);
        campaignStatusLabel.setFont(campaignStatusLabel.getFont().deriveFont(Font.BOLD, 12f));
        workersStatusLabel.setFont(workersStatusLabel.getFont().deriveFont(Font.PLAIN, 12f));

        statusBox.add(campaignStatusLabel);
        statusBox.add(workersStatusLabel);
        panel.add(statusBox, BorderLayout.SOUTH);

        return panel;
    }

    private void updateCampaignDatasetsDropdown() {
        Project selectedProj = (Project) campaignProjectCombo.getSelectedItem();
        campaignDatasetCombo.removeAllItems();
        if (selectedProj == null) {
            for (DatasetMetadata d : cachedDatasets) {
                campaignDatasetCombo.addItem(d);
            }
        } else {
            for (DatasetMetadata d : cachedDatasets) {
                if (selectedProj.getId().equals(d.getProjectId())) {
                    campaignDatasetCombo.addItem(d);
                }
            }
        }
    }

    private void startCampaign() {
        DatasetMetadata selectedDs = (DatasetMetadata) campaignDatasetCombo.getSelectedItem();
        if (selectedDs == null) {
            JOptionPane.showMessageDialog(this, "Primero sube o selecciona un dataset.");
            return;
        }

        Project selectedProj = (Project) campaignProjectCombo.getSelectedItem();
        String projId = selectedProj != null ? selectedProj.getId() : selectedDs.getProjectId();

        CampaignConfig cfg = new CampaignConfig();
        cfg.setName("Campana-" + selectedDs.getName() + "-" + System.currentTimeMillis() % 1000);
        cfg.setProjectId(projId);
        cfg.setDatasetId(selectedDs.getId());
        cfg.setDatasetName(selectedDs.getName());
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
            JOptionPane.showMessageDialog(this, "Campana iniciada para el dataset '" + selectedDs.getName() +
                    "'. Despachando tareas concurrentes a los workers.");
            refreshStatus();
        })).exceptionally(ex -> {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error iniciando campana: " + ex.getMessage()));
            return null;
        });
    }

    private String getSelectedCampaignId() {
        int row = activeCampaignsTable.getSelectedRow();
        if (row >= 0 && row < cachedActiveCampaigns.size()) {
            return cachedActiveCampaigns.get(row).getCampaignId();
        }
        return null;
    }

    private void pauseSelectedCampaign() {
        String campId = getSelectedCampaignId();
        connection.pauseCampaign(campId).thenAccept(r -> SwingUtilities.invokeLater(this::refreshStatus));
    }

    private void resumeSelectedCampaign() {
        String campId = getSelectedCampaignId();
        connection.resumeCampaign(campId).thenAccept(r -> SwingUtilities.invokeLater(this::refreshStatus));
    }

    private void stopSelectedCampaign() {
        String campId = getSelectedCampaignId();
        connection.stopCampaign(campId).thenAccept(r -> SwingUtilities.invokeLater(this::refreshStatus));
    }

    // --- Subpestaña 3: Leaderboard & Modelos .nnet ---
    private JPanel buildLeaderboardTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Panel Superior: Filtros de Proyecto y Dataset
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        filterPanel.setBorder(new TitledBorder("Filtros de Clasificacion"));

        filterPanel.add(new JLabel("Proyecto:"));
        lbProjectCombo = new JComboBox<>();
        lbProjectCombo.setToolTipText("Filtra los resultados del leaderboard por proyecto");
        lbProjectCombo.addActionListener(e -> updateLbDatasetsDropdown());
        filterPanel.add(lbProjectCombo);

        filterPanel.add(new JLabel("Dataset:"));
        lbDatasetCombo = new JComboBox<>();
        lbDatasetCombo.setToolTipText("Filtra los resultados del leaderboard por un dataset especifico");
        filterPanel.add(lbDatasetCombo);

        refreshLbButton = new JButton("Filtrar / Actualizar");
        refreshLbButton.setToolTipText("Aplica los filtros y recarga la clasificacion de modelos desde el servidor");
        refreshLbButton.addActionListener(e -> refreshLeaderboard());
        filterPanel.add(refreshLbButton);

        panel.add(filterPanel, BorderLayout.NORTH);

        // Centro: Tabla del Leaderboard
        String[] cols = {
                "ID", "Dataset", "Topologia", "Transfer", "Accuracy (%)", "RMSE", "MSE",
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
                "Identificador unico de la tarea",
                "Dataset sobre el cual fue entrenada la red",
                "Topologia de capas neuronales (Entradas-Ocultas-Salidas)",
                "Funcion de activacion/transferencia en capas ocultas",
                "Porcentaje de acierto en clasificacion sobre el conjunto de test",
                "Raiz del Error Cuadratico Medio en conjunto de prueba",
                "Error Cuadratico Medio en conjunto de prueba",
                "Error Absoluto Medio promedio en conjunto de prueba",
                "Coeficiente de determinacion R2 (calidad de ajuste del modelo)",
                "Error Porcentual Absoluto Medio (%)",
                "Error maximo absoluto registrado en una sola muestra",
                "Percentil 90 del error absoluto",
                "Tiempo total de entrenamiento en segundos",
                "Epocas ejecutadas hasta convergencia o early stopping",
                "Error de entrenamiento alcanzado al finalizar",
                "Nombre del nodo (Worker) que entreno este modelo"
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

        panel.add(new JScrollPane(leaderboardTable), BorderLayout.CENTER);

        // Pie: Acciones
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));

        JButton testInferenceBtn = new JButton("Probar Inferencia en Vivo");
        testInferenceBtn.setToolTipText("Abre una ventana para ingresar entradas manuales y probar la prediccion en tiempo real");
        testInferenceBtn.addActionListener(e -> {
            int row = leaderboardTable.getSelectedRow();
            if (row < 0 || row >= currentLeaderboard.size()) {
                JOptionPane.showMessageDialog(this, "Selecciona un modelo del leaderboard primero.");
                return;
            }
            int modelRow = leaderboardTable.convertRowIndexToModel(row);
            openInferenceDialog(currentLeaderboard.get(modelRow));
        });

        downloadNnetButton = new JButton("Descargar .nnet");
        downloadNnetButton.setBackground(new Color(60, 110, 180));
        downloadNnetButton.setForeground(Color.WHITE);
        downloadNnetButton.setToolTipText("Descarga el archivo binario compilado .nnet de la red neuronal seleccionada");
        downloadNnetButton.addActionListener(e -> {
            int row = leaderboardTable.getSelectedRow();
            if (row < 0 || row >= currentLeaderboard.size()) {
                JOptionPane.showMessageDialog(this, "Selecciona una fila del leaderboard primero.");
                return;
            }
            int modelRow = leaderboardTable.convertRowIndexToModel(row);
            downloadModel(currentLeaderboard.get(modelRow));
        });

        bottom.add(testInferenceBtn);
        bottom.add(downloadNnetButton);
        panel.add(bottom, BorderLayout.SOUTH);

        return panel;
    }

    private void updateLbDatasetsDropdown() {
        Project selectedProj = (Project) lbProjectCombo.getSelectedItem();
        lbDatasetCombo.removeAllItems();
        lbDatasetCombo.addItem(null); // Opcion todos los datasets

        if (selectedProj == null) {
            for (DatasetMetadata d : cachedDatasets) {
                lbDatasetCombo.addItem(d);
            }
        } else {
            for (DatasetMetadata d : cachedDatasets) {
                if (selectedProj.getId().equals(d.getProjectId())) {
                    lbDatasetCombo.addItem(d);
                }
            }
        }
    }

    private void refreshLeaderboard() {
        Project selectedProj = (Project) lbProjectCombo.getSelectedItem();
        DatasetMetadata selectedDs = (DatasetMetadata) lbDatasetCombo.getSelectedItem();

        java.util.concurrent.CompletableFuture<String> future;
        if (selectedDs != null) {
            future = connection.getDatasetLeaderboard(selectedDs.getId());
        } else if (selectedProj != null) {
            future = connection.getProjectLeaderboard(selectedProj.getId());
        } else {
            future = connection.getLeaderboard(null);
        }

        future.thenAccept(json -> SwingUtilities.invokeLater(() -> {
            TaskResult[] results = JsonUtil.fromJson(json, TaskResult[].class);
            leaderboardModel.setRowCount(0);
            currentLeaderboard.clear();

            if (results != null) {
                for (TaskResult r : results) {
                    currentLeaderboard.add(r);
                    EvaluationMetrics m = r.getMetrics();
                    leaderboardModel.addRow(new Object[]{
                            r.getTaskId(),
                            r.getDatasetName() != null ? r.getDatasetName() : (r.getDatasetId() != null ? r.getDatasetId() : "-"),
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
        })).exceptionally(ex -> {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error actualizando leaderboard: " + ex.getMessage()));
            return null;
        });
    }

    // --- Subpestaña 4: Comparativa de Datasets ---
    private JPanel buildComparisonTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Cabecera: Selector de Proyecto y Boton Actualizar
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        headerPanel.setBorder(new TitledBorder("Proyecto para Comparar"));

        headerPanel.add(new JLabel("Seleccionar Proyecto:"));
        compProjectCombo = new JComboBox<>();
        compProjectCombo.setToolTipText("Selecciona el proyecto cuyos datasets deseas contrastar");
        headerPanel.add(compProjectCombo);

        JButton refreshCompBtn = new JButton("Actualizar Comparativa");
        refreshCompBtn.setToolTipText("Consulta y calcula la comparativa entre todos los datasets del proyecto seleccionado");
        refreshCompBtn.addActionListener(e -> refreshComparison());
        headerPanel.add(refreshCompBtn);

        panel.add(headerPanel, BorderLayout.NORTH);

        // Centro: Tablas divididas (Mejores Modelos por Dataset + Ranking Unificado)
        String[] compCols = {
                "Dataset", "ID Tarea", "Topologia", "Transfer", "Accuracy (%)", "RMSE", "MSE",
                "MAE", "R2", "Tiempo (s)", "Iter", "Worker"
        };

        bestPerDatasetModel = new DefaultTableModel(compCols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        bestPerDatasetTable = new JTable(bestPerDatasetModel);
        bestPerDatasetTable.setAutoCreateRowSorter(true);
        bestPerDatasetTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        bestPerDatasetTable.setToolTipText("Muestra el modelo mas optimo (Top 1) entrenado para cada dataset de este proyecto");
        JScrollPane topScroll = new JScrollPane(bestPerDatasetTable);
        topScroll.setBorder(new TitledBorder("1. Mejor Modelo por Cada Dataset del Proyecto (Top 1)"));

        unifiedProjectModel = new DefaultTableModel(compCols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        unifiedProjectTable = new JTable(unifiedProjectModel);
        unifiedProjectTable.setAutoCreateRowSorter(true);
        unifiedProjectTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        unifiedProjectTable.setToolTipText("Ranking completo de todas las redes neuronales entrenadas en los datasets de este proyecto");
        JScrollPane bottomScroll = new JScrollPane(unifiedProjectTable);
        bottomScroll.setBorder(new TitledBorder("2. Ranking Unificado de Modelos del Proyecto"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topScroll, bottomScroll);
        splitPane.setDividerLocation(180);
        splitPane.setResizeWeight(0.4);
        panel.add(splitPane, BorderLayout.CENTER);

        // Pie: Acciones de Inferencia y Descarga para la Comparativa
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        JButton testInferenceCompBtn = new JButton("Probar Inferencia en Vivo");
        testInferenceCompBtn.setToolTipText("Prueba inferencia interactiva con el modelo seleccionado en cualquiera de las tablas");
        testInferenceCompBtn.addActionListener(e -> {
            TaskResult sel = getSelectedComparisonResult();
            if (sel == null) {
                JOptionPane.showMessageDialog(this, "Selecciona una fila en alguna de las dos tablas primero.");
                return;
            }
            openInferenceDialog(sel);
        });

        JButton downloadCompBtn = new JButton("Descargar .nnet");
        downloadCompBtn.setBackground(new Color(60, 110, 180));
        downloadCompBtn.setForeground(Color.WHITE);
        downloadCompBtn.setToolTipText("Descarga el archivo .nnet del modelo seleccionado en cualquiera de las dos tablas");
        downloadCompBtn.addActionListener(e -> {
            TaskResult sel = getSelectedComparisonResult();
            if (sel == null) {
                JOptionPane.showMessageDialog(this, "Selecciona una fila en alguna de las dos tablas primero.");
                return;
            }
            downloadModel(sel);
        });

        actions.add(testInferenceCompBtn);
        actions.add(downloadCompBtn);
        panel.add(actions, BorderLayout.SOUTH);

        return panel;
    }

    private TaskResult getSelectedComparisonResult() {
        int topRow = bestPerDatasetTable.getSelectedRow();
        if (topRow >= 0 && topRow < currentBestPerDataset.size()) {
            int mRow = bestPerDatasetTable.convertRowIndexToModel(topRow);
            return currentBestPerDataset.get(mRow);
        }
        int btmRow = unifiedProjectTable.getSelectedRow();
        if (btmRow >= 0 && btmRow < currentUnifiedProject.size()) {
            int mRow = unifiedProjectTable.convertRowIndexToModel(btmRow);
            return currentUnifiedProject.get(mRow);
        }
        return null;
    }

    private void refreshComparison() {
        Project selectedProj = (Project) compProjectCombo.getSelectedItem();
        String projId = selectedProj != null ? selectedProj.getId() : "default-project";

        connection.getProjectLeaderboard(projId).thenAccept(json -> SwingUtilities.invokeLater(() -> {
            TaskResult[] results = JsonUtil.fromJson(json, TaskResult[].class);
            bestPerDatasetModel.setRowCount(0);
            currentBestPerDataset.clear();
            unifiedProjectModel.setRowCount(0);
            currentUnifiedProject.clear();

            if (results != null) {
                Map<String, TaskResult> bestMap = new HashMap<>();

                for (TaskResult r : results) {
                    currentUnifiedProject.add(r);
                    addResultToTableModel(unifiedProjectModel, r);

                    String dsKey = r.getDatasetName() != null ? r.getDatasetName() :
                            (r.getDatasetId() != null ? r.getDatasetId() : "Desconocido");

                    if (!bestMap.containsKey(dsKey)) {
                        bestMap.put(dsKey, r);
                    } else {
                        TaskResult existing = bestMap.get(dsKey);
                        double accNew = r.getMetrics() != null ? r.getMetrics().getAccuracy() : 0;
                        double accOld = existing.getMetrics() != null ? existing.getMetrics().getAccuracy() : 0;
                        if (accNew > accOld) {
                            bestMap.put(dsKey, r);
                        } else if (accNew == accOld) {
                            double rmseNew = r.getMetrics() != null ? r.getMetrics().getRmse() : 999;
                            double rmseOld = existing.getMetrics() != null ? existing.getMetrics().getRmse() : 999;
                            if (rmseNew < rmseOld) {
                                bestMap.put(dsKey, r);
                            }
                        }
                    }
                }

                for (TaskResult best : bestMap.values()) {
                    currentBestPerDataset.add(best);
                    addResultToTableModel(bestPerDatasetModel, best);
                }
            }
        })).exceptionally(ex -> {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error actualizando comparativa: " + ex.getMessage()));
            return null;
        });
    }

    private void addResultToTableModel(DefaultTableModel model, TaskResult r) {
        EvaluationMetrics m = r.getMetrics();
        model.addRow(new Object[]{
                r.getDatasetName() != null ? r.getDatasetName() : (r.getDatasetId() != null ? r.getDatasetId() : "-"),
                r.getTaskId(),
                r.getNetworkConfig() != null ? r.getNetworkConfig().getTopologySummary() : "-",
                r.getNetworkConfig() != null ? r.getNetworkConfig().getTransferFunction() : "-",
                m != null ? String.format("%.2f", m.getAccuracy()) : "-",
                m != null ? String.format("%.4f", m.getRmse()) : "-",
                m != null ? String.format("%.4f", m.getMse()) : "-",
                m != null ? String.format("%.4f", m.getMae()) : "-",
                m != null ? String.format("%.4f", m.getR2()) : "-",
                m != null ? String.format("%.2f", m.getTiempoSeg()) : "-",
                m != null ? m.getIteraciones() : "-",
                r.getWorkerName()
        });
    }

    // --- Inferencia Interactiva & Descarga de Modelos ---
    private void openInferenceDialog(TaskResult selected) {
        connection.downloadModel(selected.getCampaignId(), selected.getTaskId()).thenAccept(b64 -> SwingUtilities.invokeLater(() -> {
            try {
                org.neuroph.core.NeuralNetwork<?> net = com.neuroph.train.common.util.NetworkSerializer.fromBase64(b64);
                int inputCount = net.getInputsCount();

                JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this),
                        "Prueba de Inferencia - " + selected.getTaskId(), true);
                dialog.setLayout(new BorderLayout(10, 10));
                dialog.setSize(450, 400);
                dialog.setLocationRelativeTo(this);

                JPanel inputsPanel = new JPanel(new GridLayout(inputCount, 2, 5, 5));
                inputsPanel.setBorder(new TitledBorder("Valores de Entrada (Sensores)"));
                JTextField[] inFields = new JTextField[inputCount];
                for (int i = 0; i < inputCount; i++) {
                    inputsPanel.add(new JLabel("Entrada " + i + ":"));
                    inFields[i] = new JTextField("0.5");
                    inFields[i].setToolTipText("Valor numerico para la entrada de red " + i);
                    inputsPanel.add(inFields[i]);
                }

                JPanel resultPanel = new JPanel(new BorderLayout());
                resultPanel.setBorder(new TitledBorder("Prediccion de la Red Neuronal"));
                JTextArea resArea = new JTextArea(5, 30);
                resArea.setEditable(false);
                resultPanel.add(new JScrollPane(resArea), BorderLayout.CENTER);

                JButton calcBtn = new JButton("Calcular Salida");
                calcBtn.setBackground(new Color(40, 140, 40));
                calcBtn.setForeground(Color.WHITE);
                calcBtn.setToolTipText("Evalua la red neuronal cargada con los valores ingresados");
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
                            sb.append(String.format("Clase Predicha: %s (Activacion: %.2f%%)\n",
                                    labels.get(bestIdx), maxV * 100.0));
                        }
                        resArea.setText(sb.toString());
                    } catch (Exception ex) {
                        resArea.setText("Error en calculo: " + ex.getMessage());
                    }
                });

                dialog.add(new JScrollPane(inputsPanel), BorderLayout.NORTH);
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

    private void downloadModel(TaskResult selected) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("modelo_" + selected.getTaskId() + ".nnet"));

        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File targetFile = chooser.getSelectedFile();
            connection.downloadModel(selected.getCampaignId(), selected.getTaskId()).thenAccept(b64 -> SwingUtilities.invokeLater(() -> {
                try {
                    byte[] bytes = Base64.getDecoder().decode(b64);
                    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                        fos.write(bytes);
                    }
                    JOptionPane.showMessageDialog(this, "Archivo .nnet guardado con exito en:\n" + targetFile.getAbsolutePath());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Error guardando archivo: " + ex.getMessage());
                }
            })).exceptionally(ex -> {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error descargando: " + ex.getMessage()));
                return null;
            });
        }
    }

    // --- Actualización de Datos y Estado desde el Servidor ---
    private void refreshServerProjectsAndDatasets(Runnable onComplete) {
        connection.getStatus().thenAccept(json -> SwingUtilities.invokeLater(() -> {
            Map<String, Object> map = JsonUtil.fromJson(json, Map.class);
            if (map != null) {
                cachedProjects.clear();
                if (map.containsKey("projects")) {
                    List<Map<String, Object>> pList = (List<Map<String, Object>>) map.get("projects");
                    for (Map<String, Object> pm : pList) {
                        Project p = new Project();
                        p.setId((String) pm.get("id"));
                        p.setName((String) pm.get("name"));
                        cachedProjects.add(p);
                    }
                }

                cachedDatasets.clear();
                if (map.containsKey("datasets")) {
                    List<Map<String, Object>> dList = (List<Map<String, Object>>) map.get("datasets");
                    for (Map<String, Object> dm : dList) {
                        DatasetMetadata d = new DatasetMetadata();
                        d.setId((String) dm.get("id"));
                        d.setName((String) dm.get("name"));
                        d.setProjectId((String) dm.get("projectId"));
                        d.setProjectName((String) dm.get("projectName"));
                        cachedDatasets.add(d);
                    }
                }

                // Actualizar combos de proyectos
                uploadProjectCombo.removeAllItems();
                campaignProjectCombo.removeAllItems();
                lbProjectCombo.removeAllItems();
                lbProjectCombo.addItem(null); // Opcion todos los proyectos
                compProjectCombo.removeAllItems();

                for (Project p : cachedProjects) {
                    uploadProjectCombo.addItem(p);
                    campaignProjectCombo.addItem(p);
                    lbProjectCombo.addItem(p);
                    compProjectCombo.addItem(p);
                }

                updateCampaignDatasetsDropdown();
                updateLbDatasetsDropdown();

                refreshStatus();

                if (onComplete != null) {
                    onComplete.run();
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
                campaignStatusLabel.setText(String.format("Tareas globales: %d pendientes | %d en progreso | %d completadas",
                        pending, running, completed));

                // Actualizar tabla de campañas activas
                activeCampaignsModel.setRowCount(0);
                cachedActiveCampaigns.clear();

                if (map.containsKey("activeCampaigns")) {
                    List<Map<String, Object>> camps = (List<Map<String, Object>>) map.get("activeCampaigns");
                    for (Map<String, Object> cm : camps) {
                        CampaignConfig c = new CampaignConfig();
                        c.setCampaignId((String) cm.get("campaignId"));
                        c.setName((String) cm.get("name"));
                        c.setProjectId((String) cm.get("projectId"));
                        c.setDatasetId((String) cm.get("datasetId"));
                        c.setDatasetName((String) cm.get("datasetName"));
                        c.setStatus((String) cm.get("status"));
                        c.setPatience(cm.containsKey("patience") ? ((Number) cm.get("patience")).intValue() : 80);
                        c.setTargetError(cm.containsKey("targetError") ? ((Number) cm.get("targetError")).doubleValue() : 0.01);
                        cachedActiveCampaigns.add(c);

                        activeCampaignsModel.addRow(new Object[]{
                                c.getCampaignId(),
                                c.getName(),
                                c.getProjectId(),
                                c.getDatasetName() != null ? c.getDatasetName() : c.getDatasetId(),
                                cm.get("heuristicType"),
                                c.getStatus(),
                                c.getPatience(),
                                String.format("%.4f", c.getTargetError())
                        });
                    }
                }
            }
        }));
    }
}
