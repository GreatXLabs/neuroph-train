# Neuroph-Train 🧠⚡

Sistema distribuido de alto rendimiento en **Java puro sobre sockets TCP** para la orquestación, exploración heurística y entrenamiento concurrente de Redes Neuronales Artificiales mediante **Neuroph v2.98**.

Diseñado para operar con un **Servidor en VPS (Dokploy)** y **Clientes distribuidos en redes hogareñas** (detrás de routers/NAT sin apertura de puertos), explorando topologías e hiperparámetros mediante **Búsqueda Guiada (Adaptive Hill-Climbing)** y **Búsqueda Evolutiva (Algoritmo Genético)**.

---

## 📥 Descargas Rápidas (Versión Precompilada)

Puedes descargar la última versión compilada directamente desde los [Releases de GitHub](https://github.com/GreatXLabs/neuroph-train/releases/latest):

- 📦 **[neuroph-client-dist.zip](https://github.com/GreatXLabs/neuroph-train/releases/latest/download/neuroph-client-dist.zip)** (Recomendado para compañeros): Contiene el `.jar`, archivo de configuración y lanzadores para Windows (`.bat`) y Linux/Mac (`.sh`).
- 💻 **[neuroph-client.jar](https://github.com/GreatXLabs/neuroph-train/releases/latest/download/neuroph-client.jar)**: Ejecutable independiente del cliente (interfaz gráfica Swing y modo CLI headless).
- 🖥️ **[neuroph-server.jar](https://github.com/GreatXLabs/neuroph-train/releases/latest/download/neuroph-server.jar)**: Ejecutable independiente del servidor orquestador.

---

## 🌟 Características Principales

1. **Topología de Red Saliente (NAT-Friendly)**:
   - Los clientes inician la conexión TCP hacia el servidor. No requiere abrir puertos en las casas de los participantes.
   - Heartbeat bidireccional automático con tolerancia a caídas y reencolado de tareas no finalizadas.
2. **Asignación en Caliente de Recursos (Hot Resize)**:
   - Selector deslizante en la interfaz gráfica para cambiar dinámicamente la cantidad de núcleos de CPU prestados al cluster sin reiniciar la aplicación ni interrumpir las tareas activas.
3. **Doble Motor Heurístico de Búsqueda de Redes**:
   - **Búsqueda Guiada**: Explora variaciones locales de topología y learning rate con *Random Restarts* para no estancarse en mínimos locales.
   - **Búsqueda Evolutiva**: Algoritmo genético con selección por torneo, cruce, mutación y fitness basado en precisión con penalización de parsimonia.
4. **Métricas Estadísticas Exhaustivas**:
   - Calcula sobre el 30% de prueba: `MSE`, `RMSE`, `MAE`, `R2`, `MAPE`, `MaxError`, `MinError`, `Percentil90`, `TiempoSeg`, `Iteraciones`, `ErrorFinal`, `Accuracy (%)`, `Matriz de Confusión` y `F1-Score`.
5. **Panel de Administración con Descarga y Probador en Vivo**:
   - Carga dinámica de cualquier dataset CSV.
   - Tabla de clasificación (*Leaderboard*) en tiempo real.
   - Descarga directa de archivos `.nnet` de Neuroph.
   - Diálogo interactivo para probar inferencia en vivo con valores de sensores antes de cargar el modelo en el robot.
6. **Modo Headless (Consola)**:
   - Para ejecutar workers en servidores o terminales sin pantalla:
     ```bash
     java -jar neuroph-client.jar --headless --host=neuroph.aguilucho.ar --port=443 --cores=4 --name=Nodo-01
     ```

---

## 🚀 Despliegue del Servidor (Dokploy / Docker)

### Opción 1: Docker Compose
1. Configura el archivo `.env`:
   ```bash
   SERVER_PORT=9000
   ADMIN_TOKEN=tu-token-secreto
   STORAGE_DIR=/app/storage
   HEARTBEAT_TIMEOUT=15
   ```
2. Inicia el contenedor:
   ```bash
   docker compose up -d --build
   ```

### Opción 2: Ejecución Directa con Java
```bash
java -jar neuroph-server.jar
```

---

## 🛠️ Compilación desde el Código Fuente

Requisitos: **Java 21** y **Maven 3.9+**.

```bash
# Clonar repositorio
git clone https://github.com/GreatXLabs/neuroph-train.git
cd neuroph-train

# Compilar y ejecutar pruebas
mvn clean test

# Generar ejecutables .jar y paquete .zip
mvn clean package
bash scripts/build-client-dist.sh
```

Los artefactos se generarán en:
- `target/neuroph-server.jar`
- `target/neuroph-client.jar`
- `dist/neuroph-client-dist.zip`

---

## 📄 Licencia

Desarrollado para el Proyecto de Programación sobre Redes y Desarrollo de Sistemas.
