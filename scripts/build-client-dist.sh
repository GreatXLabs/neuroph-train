#!/usr/bin/env bash
set -e

echo "=== Compilando neuroph-client.jar con Maven ==="
mvn clean package -DskipTests

DIST_DIR="dist/neuroph-client"
rm -rf "$DIST_DIR" dist/neuroph-client-dist.zip
mkdir -p "$DIST_DIR"

echo "=== Copiando archivos a $DIST_DIR ==="
cp target/neuroph-client.jar "$DIST_DIR/neuroph-client.jar"

cat << 'EOF' > "$DIST_DIR/client.properties"
# Configuración del Cliente de Entrenamiento Neuroph
# Configura aquí la IP de la VPS donde corre el servidor
server.host=127.0.0.1
server.port=9000
worker.name=Compañero-PC
# Cantidad de núcleos asignados (puedes cambiarlo en caliente desde la aplicación)
worker.cores=2
EOF

cat << 'EOF' > "$DIST_DIR/start-client.sh"
#!/usr/bin/env bash
java -jar neuroph-client.jar
EOF
chmod +x "$DIST_DIR/start-client.sh"

cat << 'EOF' > "$DIST_DIR/start-client.bat"
@echo off
start javaw -jar neuroph-client.jar
EOF

echo "=== Generando neuroph-client-dist.zip para distribuir a los compañeros ==="
(cd dist && zip -r neuroph-client-dist.zip neuroph-client)

echo "=== ¡Listo! Archivo disponible en dist/neuroph-client-dist.zip ==="
