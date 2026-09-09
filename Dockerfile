# Multi-stage Dockerfile para el Servidor Orquestador Neuroph-Train en Dokploy

# Etapa 1: Compilación con Maven y JDK 21
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /build

COPY pom.xml .
# Descargar dependencias para cache de Docker
RUN mvn dependency:go-offline -B || true

COPY src ./src
RUN mvn clean package -DskipTests

# Etapa 2: Imagen final ligera con JRE 21
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Crear directorio para volumen persistente
RUN mkdir -p /app/storage

# Copiar el fat jar del servidor compilado
COPY --from=builder /build/target/neuroph-server.jar /app/neuroph-server.jar

# Variables de entorno por defecto
ENV SERVER_PORT=9000
ENV STORAGE_DIR=/app/storage
ENV ADMIN_TOKEN=secret-orquitas-2026
ENV HEARTBEAT_TIMEOUT=15

# Puerto TCP expuesto
EXPOSE 9000

# Volumen persistente para datasets y modelos .nnet
VOLUME ["/app/storage"]

CMD ["java", "-Xmx2g", "-jar", "/app/neuroph-server.jar"]
