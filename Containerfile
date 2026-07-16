# ============================================================================
# Multi-stage Containerfile for ATS Validator Service
# Compatible with Docker and Podman
# Builder : maven:3.6.3-openjdk-11 (Debian Buster)
# Runtime : eclipse-temurin:11-jre-jammy (Ubuntu 22.04 LTS)
# ============================================================================

# ----------------------------------------------------------------------------
# Stage 1: Builder - compile the project
# ----------------------------------------------------------------------------
FROM docker.io/maven:3.6.3-openjdk-11 AS builder

WORKDIR /build

# Copy Maven configuration first for better layer caching
COPY pom.xml .

# Copy SRI proprietary JARs (not available on Maven Central)
COPY libs/ ./libs/

# Install ONLY the SRI proprietary JARs (ec.gob.sri.anexo) into the local
# Maven repository. All other JARs (Saxon-HE, commons-lang, guava, hsqldb,
# ehcache, log4j, joda-time) are downloaded from Maven Central.
#
# Explicitly excluded due to conflicts with Spring Boot 2.7.x:
#   - hibernate-validator-4.0.2.GA  -> Bean Validation 1.0, incompatible
#   - validation-api-1.0.0.GA       -> replaced by jakarta.validation 2.x
#   - slf4j-*                       -> conflict with logback-classic
RUN for jar in \
      libs/rig-ats-validacion-2.23.0.jar \
      libs/rig-ats-persistencia-2.23.0.jar \
      libs/rig-ats-persistencia-api-2.23.0.jar \
      libs/rig-anexos-persistencia-1.0.0.jar \
      libs/rig-anexos-validacion-4.12.0.jar \
      libs/api-anexos-1.0.0.jar \
      libs/catalogos-api-3.6.0.jar \
      libs/catalogos-carga-3.6.0.jar \
      libs/catalogos-impl-3.6.0.jar \
      libs/catalogos-modelo-3.6.0.jar; \
    do \
      nombre=$(basename "$jar" .jar); \
      version=$(echo "$nombre" | grep -oP '\d+\.\d+(\.\d+)?$'); \
      artifactId=$(echo "$nombre" | sed "s/-${version}//"); \
      echo "Installing SRI jar: ec.gob.sri.anexo:${artifactId}:${version}"; \
      mvn install:install-file \
        -Dfile="$jar" \
        -DgroupId=ec.gob.sri.anexo \
        -DartifactId="${artifactId}" \
        -Dversion="${version}" \
        -Dpackaging=jar \
        -DgeneratePom=true \
        -DcreateChecksum=true -q; \
    done

# Pre-fetch dependencies (cached if pom.xml does not change)
RUN mvn dependency:go-offline -q || true

# Copy source code
COPY src/ ./src/

# Build the project (skip tests to speed up)
RUN mvn package -DskipTests -q

# Verify the JAR was produced
RUN ls -lh target/*.jar && \
    test -f target/ats-validator-*.jar || (echo "ERROR: JAR not found" && exit 1)

# ----------------------------------------------------------------------------
# Stage 2: Runtime - lean final image
# eclipse-temurin is the official OpenJDK image maintained by Eclipse Adoptium.
# Tag 11-jre-jammy: JRE 11 on Ubuntu 22.04 LTS (Jammy Jellyfish).
# (openjdk:11-jre-slim was deprecated; eclipse-temurin is its official successor)
# ----------------------------------------------------------------------------
FROM docker.io/eclipse-temurin:11-jre-jammy

LABEL maintainer="ATS Validator" \
      description="REST service for SRI Ecuador ATS validation" \
      version="1.0.0"

# curl is required by the HEALTHCHECK below; the eclipse-temurin base image
# does not include it
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Create a non-root user for security
RUN groupadd -r atsvalidator && \
    useradd -r -g atsvalidator -m -d /app -s /bin/bash atsvalidator

WORKDIR /app

# Copy the fat JAR from the builder stage
COPY --from=builder /build/target/ats-validator-*.jar ./app.jar

# Copy the cache configuration file required by the SRI libraries
COPY src/main/resources/rig-catalogos-ehcache.xml /app/

# Create the cache directory expected by the SRI catalog loader
RUN mkdir -p /app/.dimmData/ehcache && \
    cp /app/rig-catalogos-ehcache.xml /app/.dimmData/ehcache/ && \
    chown -R atsvalidator:atsvalidator /app

USER atsvalidator

ENV JAVA_OPTS="-Xms256m -Xmx1024m" \
    SERVER_PORT=8080 \
    HOME=/app

EXPOSE 8080

# Wait up to 60 s for the SRI catalog load before marking as healthy
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:${SERVER_PORT}/api/ats/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --server.port=$SERVER_PORT"]
