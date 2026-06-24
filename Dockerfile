# --- Etapa 1: build ---
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /build

COPY pom.xml .
COPY libs ./libs

# Instalar las librerías del SRI en el repo Maven local (no existen en Maven Central)
RUN for jar in libs/*.jar; do \
      nombre=$(basename "$jar" .jar); \
      version=$(echo "$nombre" | grep -oP '[0-9]+\.[0-9]+\.[0-9]+.*$'); \
      artifactId=$(echo "$nombre" | sed "s/-${version}//"); \
      mvn install:install-file \
        -Dfile="$jar" \
        -DgroupId=ec.gob.sri.anexo \
        -DartifactId="$artifactId" \
        -Dversion="$version" \
        -Dpackaging=jar \
        -DgeneratePom=true \
        -DcreateChecksum=true -q; \
    done

COPY src ./src
RUN mvn package -q -DskipTests

# --- Etapa 2: runtime ---
FROM eclipse-temurin:11-jre
WORKDIR /app

# Config de caché que requieren las librerías del SRI
RUN mkdir -p /root/.dimmData/ehcache
COPY src/main/resources/rig-catalogos-ehcache.xml /root/.dimmData/ehcache/rig-catalogos-ehcache.xml

COPY --from=build /build/target/ats-validator-1.0.0.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
