package com.neuroph.train.common.protocol;

/**
 * Tipos de mensajes intercambiados a través del socket TCP entre Clientes y Servidor.
 */
public enum MessageType {
    // Autenticación de Administrador
    AUTH_REQUEST,
    AUTH_RESPONSE,

    // Registro y estado de Workers
    WORKER_REGISTER,
    WORKER_REGISTER_ACK,
    WORKER_UPDATE_RESOURCES,
    WORKER_STATUS_ACK,

    // Keep-alive / Heartbeat
    HEARTBEAT,
    HEARTBEAT_ACK,

    // Sincronización de Datasets
    SYNC_DATASET_REQUEST,
    SYNC_DATASET_RESPONSE,

    // Asignación y ejecución de tareas de entrenamiento
    TASK_ASSIGN,
    TASK_PROGRESS,
    TASK_RESULT,
    TASK_CANCEL,

    // Acciones de Administrador
    ADMIN_UPLOAD_DATASET,
    ADMIN_START_CAMPAIGN,
    ADMIN_PAUSE_CAMPAIGN,
    ADMIN_STOP_CAMPAIGN,
    ADMIN_GET_STATUS,
    ADMIN_GET_LEADERBOARD,
    ADMIN_DOWNLOAD_MODEL,

    // Respuestas genéricas y errores
    SUCCESS_RESPONSE,
    ERROR_RESPONSE
}
