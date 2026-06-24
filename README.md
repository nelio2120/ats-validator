# ATS Validator — Servicio de Validación SRI Ecuador

Servicio REST en Java/Spring Boot que recibe un XML del **Anexo Transaccional Simplificado (ATS)** del SRI Ecuador y devuelve:

- Errores de estructura XSD
- Errores de reglas de negocio del SRI (las mismas que aplica el DIMM)
- Advertencias
- Talón resumen en HTML (si el documento es válido)

---

## Prerrequisitos

| Requisito | Versión mínima |
|-----------|----------------|
| Java (JDK) | 11 o superior |
| Maven | 3.6+ (solo para recompilar) |

Verificar Java instalado:

```bash
java -version
```

---

## Estructura del proyecto

```
ats-validator/
├── libs/                             # JARs del SRI (22 librerías extraídas del DIMM)
├── src/
│   └── main/
│       ├── java/ec/sri/ats/
│       │   ├── AtsValidatorApplication.java     # Main Spring Boot
│       │   ├── config/DatabaseConfig.java        # Configura HSQLDB embebida
│       │   ├── controller/AtsController.java     # Endpoints REST
│       │   ├── model/ValidationResponse.java     # Modelo de respuesta
│       │   └── service/
│       │       ├── AtsValidationService.java     # Lógica de validación
│       │       └── GeneradorTalonServicio.java   # Generador HTML talón
│       └── resources/
│           ├── application.properties
│           ├── at.xsd                            # Esquema oficial ATS
│           ├── db/bdd_dimm_anexos.*              # Catálogos SRI (HSQLDB)
│           ├── rig-catalogos-ehcache.xml         # Config caché
│           └── talon/                            # Plantillas HTML del talón
├── target/
│   └── ats-validator-1.0.0.jar                  # Fat JAR listo para ejecutar
└── pom.xml
```

---

## Paso 1 — Preparación del entorno (solo la primera vez)

El servicio necesita un archivo de configuración de caché en el directorio del usuario. Crear la carpeta y copiar el archivo:

```bash
mkdir -p ~/.dimmData/ehcache

cp src/main/resources/rig-catalogos-ehcache.xml \
   ~/.dimmData/ehcache/rig-catalogos-ehcache.xml
```

> Este archivo le indica a las librerías del SRI dónde almacenar el caché de catálogos
> (códigos de países, porcentajes IVA, tipos de comprobantes, etc.).

---

## Paso 2 — Instalar las librerías del SRI en Maven (solo la primera vez)

Las librerías del SRI no están en Maven Central. Hay que instalarlas al repositorio local
(`~/.m2`). Desde la raíz del proyecto ejecutar:

```bash
for jar in libs/*.jar; do
  nombre=$(basename "$jar" .jar)
  version=$(echo "$nombre" | grep -oP '[\d]+\.[\d]+\.[\d]+.*$')
  artifactId=$(echo "$nombre" | sed "s/-${version}//")
  mvn install:install-file \
    -Dfile="$jar" \
    -DgroupId=ec.gob.sri.anexo \
    -DartifactId="$artifactId" \
    -Dversion="$version" \
    -Dpackaging=jar \
    -DgeneratePom=true \
    -DcreateChecksum=true
done
```

---

## Paso 3 — Compilar (opcional)

Si solo se quiere ejecutar el JAR ya compilado en `target/`, saltar este paso.

Para recompilar desde cero:

```bash
mvn package -q
```

El JAR resultante queda en `target/ats-validator-1.0.0.jar` (~29 MB, autocontenido).

---

## Paso 4 — Ejecutar el servicio

```bash
java -jar target/ats-validator-1.0.0.jar
```

Puerto por defecto: **8080**. Para usar otro puerto:

```bash
java -jar target/ats-validator-1.0.0.jar --server.port=8085
```

Salida esperada al arrancar:

```
INFO  DatabaseConfig       - Catálogos SRI cargados desde: /tmp/dimm-catalogs-xxx
INFO  AtsValidationService - Catálogos SRI inicializados en caché: true
INFO  AtsValidatorApplication - Started AtsValidatorApplication in 2.3 seconds
```

El servicio está listo cuando aparece `Started AtsValidatorApplication`.

---

## API REST

### Health check

```
GET http://localhost:8080/api/ats/health
```

Respuesta:

```json
{"status":"UP","servicio":"ATS Validator SRI Ecuador"}
```

---

### Validar un XML ATS

**Opción A — Enviar el XML como body**

```bash
curl -X POST http://localhost:8080/api/ats/validar \
     -H "Content-Type: application/xml" \
     --data-binary @mi-archivo-ats.xml
```

**Opción B — Enviar como formulario multipart**

```bash
curl -X POST http://localhost:8080/api/ats/validar \
     -F "archivo=@mi-archivo-ats.xml"
```

---

### Formato de respuesta

```json
{
  "valido": false,
  "erroresXsd": [],
  "errores": [
    "El CONCEPTO RETENCIÓN EN LA FUENTE DE IMPUESTO A LA RENTA reportado [501] no es válido para tipo de pago."
  ],
  "advertencias": [],
  "talonHtml": null
}
```

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `valido` | boolean | `true` solo si no hay errores XSD ni de negocio |
| `erroresXsd` | array | Errores de estructura del XML contra el esquema `at.xsd` |
| `errores` | array | Errores de reglas de negocio del SRI |
| `advertencias` | array | Advertencias (no bloquean la validez) |
| `talonHtml` | string / null | HTML del talón resumen autocontenido (CSS y logo embebidos); solo presente cuando `valido: true`. Se puede guardar directamente como `.html` y abrir en cualquier navegador sin dependencias externas. |

---

## Etapas de validación

```
XML recibido
    │
    ▼
[1] Validación XSD ──── falla ──► erroresXsd: [...], valido: false
    │ pasa
    ▼
[2] Reglas de negocio SRI (ValidacionAnexoAts)
[3] Validación de duplicados (ValidacionDuplicadosAts)
    │
    ├── con errores ──► errores: [...], valido: false
    │
    └── sin errores ──► talón HTML generado, valido: true
```

---

## Integración desde Odoo (Python)

```python
import requests

def validar_ats(xml_content: str, url: str = "http://localhost:8080") -> dict:
    response = requests.post(
        f"{url}/api/ats/validar",
        data=xml_content.encode("utf-8"),
        headers={"Content-Type": "application/xml"},
        timeout=60,
    )
    response.raise_for_status()
    return response.json()

# Uso
resultado = validar_ats(xml_string)

if resultado["valido"]:
    talon_html = resultado["talonHtml"]
    print("ATS válido")
else:
    for error in resultado["errores"]:
        print("Error:", error)
    for error in resultado["erroresXsd"]:
        print("Error XSD:", error)
```

---

## Configuración avanzada

El archivo `src/main/resources/application.properties` contiene los valores por defecto:

```properties
server.port=8080

# Tamaño máximo de XML aceptado
spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=50MB

# Nivel de log
logging.level.ec.sri.ats=INFO
logging.level.ec.gob.sri=WARN
```

Cualquier propiedad se puede sobreescribir en tiempo de ejecución:

```bash
java -jar target/ats-validator-1.0.0.jar \
     --server.port=9090 \
     --logging.level.ec.sri.ats=DEBUG
```

---

## Despliegue con Docker

El servicio está publicado en producción en:

**https://validator.ats.erp360app.com**

```
GET  https://validator.ats.erp360app.com/api/ats/health
POST https://validator.ats.erp360app.com/api/ats/validar
```

### Levantar con docker compose

El repo incluye un `Dockerfile` (build multi-stage: Maven instala las librerías del SRI desde
`libs/` y compila; la imagen final corre sobre `eclipse-temurin:11-jre`) y un
`docker-compose.yml`:

```bash
docker compose build
docker compose up -d
```

El servicio queda expuesto en `127.0.0.1:8090` (mapeado a `8080` dentro del contenedor). En el
servidor de producción, un nginx hace de proxy reverso con TLS (Let's Encrypt) hacia ese puerto
para servir el dominio `validator.ats.erp360app.com`.

> Nota: la dependencia `hibernate-validator 4.0.2.GA` se removió de `pom.xml` — entraba en
> conflicto con la versión de `validation-api` que impone el padre de Spring Boot
> (`spring-boot-starter-parent`) y rompía el arranque. El código propio no usa Bean Validation,
> así que no afecta la funcionalidad.

---

## Notas técnicas

- El JAR es **autocontenido** — incluye Tomcat embebido, los catálogos HSQLDB y todas las
  plantillas del talón. No requiere instalación adicional más allá de Java.
- Los catálogos SRI se cargan en memoria al arrancar (~2 segundos). Las validaciones
  posteriores son prácticamente instantáneas.
- El servicio es **stateless** — cada petición es completamente independiente.
- Probado con JDK 11, 17 y 21.
- Tamaño del JAR: ~29 MB.
