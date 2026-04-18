package ec.sri.ats.service;

import ec.gob.sri.anexo.ats.persistencia.api.analisis.AnalizadorAtsPersistencia;
import ec.gob.sri.anexo.ats.validacion.ValidacionAnexoAts;
import ec.gob.sri.anexo.catalogo.api.enums.FuenteEnum;
import ec.gob.sri.anexo.catalogo.carga.impl.AdministradorCargaCatalogosImpl;
import ec.gob.sri.anexo.ats.validacion.ValidacionDuplicadosAts;
import ec.gob.sri.anexo.ats.validacion.util.ArgumentosAdicionalesValidacion;
import ec.gob.sri.anexo.ats.validacion.util.ArgumentosValidacion;
import ec.gob.sri.anexo.ats.validacion.util.OrigenValidacion;
import ec.gob.sri.anexo.validacion.modelo.ContextoErrores;
import ec.gob.sri.anexo.validacion.modelo.ErrorDetalleAnexoValidacion;
import ec.gob.sri.anexo.validacion.modelo.ErrorValidacion;
import ec.gob.sri.anexo.validacion.modelo.WarningValidacion;
import ec.sri.ats.model.ValidationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.sql.DataSource;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Servicio principal de validación ATS.
 *
 * Etapas:
 *  1. Validación XSD        — contra el esquema oficial (at.xsd)
 *  2. Validación de negocio — reglas del SRI (rig-ats-validacion)
 *  3. Validación duplicados — rig-ats-validacion
 *  4. Generación talón      — HTML resumen (AnalizadorAtsPersistencia)
 */
@Service
public class AtsValidationService {

    private static final Logger log = LoggerFactory.getLogger(AtsValidationService.class);
    private static final int MAX_ERRORES = 500;

    @Autowired
    private DataSource hsqlDataSource;

    // ── Inicialización del caché de catálogos ─────────────────────────────

    @PostConstruct
    public void inicializarCatalogos() {
        try (Connection conn = hsqlDataSource.getConnection()) {
            AdministradorCargaCatalogosImpl admin =
                AdministradorCargaCatalogosImpl.getInstancia(FuenteEnum.DIMM);
            boolean ok = admin.inicializarCatalogos(conn, FuenteEnum.DIMM);
            log.info("Catálogos SRI inicializados en caché: {}", ok);
        } catch (Exception e) {
            log.warn("No se pudo inicializar caché de catálogos SRI: {}", e.getMessage());
        }
    }

    // ── Punto de entrada ──────────────────────────────────────────────────

    public ValidationResponse validar(String xmlContent) {
        ValidationResponse response = new ValidationResponse();
        File tmpXml = null;

        try {
            tmpXml = escribirArchivoTemporal(xmlContent);

            // 1. Validación XSD
            List<String> xsdErrors = validarXSD(tmpXml);
            response.setErroresXsd(xsdErrors);

            // 2. Validación de negocio (solo si pasa el XSD)
            if (xsdErrors.isEmpty()) {
                validarNegocioYDuplicados(tmpXml, response);
            } else {
                response.setValido(false);
            }

            // 3. Talón (siempre que no haya errores de negocio graves)
            if (response.getErrores().isEmpty() && response.getErroresXsd().isEmpty()) {
                response.setTalonHtml(generarTalon(tmpXml));
                response.setValido(true);
            }

        } catch (Exception e) {
            log.error("Error inesperado en validación", e);
            response.setValido(false);
            response.getErrores().add("Error interno: " + e.getMessage());
        } finally {
            if (tmpXml != null) tmpXml.delete();
        }

        return response;
    }

    // ── Etapa 1: Validación XSD ───────────────────────────────────────────

    private List<String> validarXSD(File xmlFile) {
        List<String> errores = new ArrayList<>();
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            try (InputStream xsdStream = getClass().getResourceAsStream("/at.xsd")) {
                if (xsdStream == null) {
                    log.warn("at.xsd no encontrado en classpath, omitiendo validación XSD");
                    return errores;
                }
                Schema schema = factory.newSchema(new StreamSource(xsdStream));
                Validator validator = schema.newValidator();

                final List<String> errXsd = errores;
                validator.setErrorHandler(new DefaultHandler() {
                    @Override public void error(SAXParseException e) {
                        errXsd.add("Error XSD (línea " + e.getLineNumber() + "): " + e.getMessage());
                    }
                    @Override public void fatalError(SAXParseException e) throws SAXException {
                        errXsd.add("Error fatal XSD (línea " + e.getLineNumber() + "): " + e.getMessage());
                        throw e;
                    }
                });

                validator.validate(new StreamSource(xmlFile));
            }
        } catch (SAXException e) {
            // El error ya fue capturado por el handler
        } catch (Exception e) {
            log.error("Fallo en validación XSD", e);
            errores.add("Error al validar XSD: " + e.getMessage());
        }
        return errores;
    }

    // ── Etapa 2: Validación de negocio + duplicados ───────────────────────

    private void validarNegocioYDuplicados(File xmlFile, ValidationResponse response) {
        Connection conn = null;
        try {
            conn = hsqlDataSource.getConnection();

            ContextoErrores contexto = new ContextoErrores(MAX_ERRORES);

            // Leer año y mes del XML para el contexto
            int[] periodoXml = leerPeriodoDelXml(xmlFile);
            contexto.setAnio(periodoXml[0]);
            contexto.setMes(periodoXml[1]);

            /*
             * El validador del SRI usa un único array plano de varargs compartido.
             * Índices confirmados por bytecode (javap):
             *   args[0] = codigoAnexo  (Long)           → ArgumentosValidacion  iconst_0
             *   args[1] = ruta         (String)          → ArgumentosValidacion  iconst_1
             *   args[2] = (no leído por ninguna clase)
             *   args[3] = contexto     (ContextoErrores) → ArgumentosValidacion  iconst_3
             *   args[4] = codigoCab    (Long)            → ArgumentosValidacion  iconst_4
             *   args[5] = archivoAnexo (File)            → ArgumentosAdicionalesValidacion  iconst_5
             *   args[6] = conexion     (Connection)      → ArgumentosAdicionalesValidacion  bipush 6
             */
            Object[] args = {
                1L,                         // [0] codigoAnexo — debe ser Long no-null (el validador llama .longValue())
                xmlFile.getAbsolutePath(),  // [1] ruta
                null,                       // [2] no utilizado
                contexto,                   // [3] contextoErrores
                1L,                         // [4] codigoCabecera — también Long (precaución)
                xmlFile,                    // [5] archivoAnexo
                conn                        // [6] conexion HSQLDB
            };

            // Validación de información
            ValidacionAnexoAts validacionInfo = new ValidacionAnexoAts();
            inyectarDataSource(validacionInfo, "dataSource", hsqlDataSource);
            validacionInfo.validar(args);

            // Validación de duplicados
            // ExcepcionErroresExcedidos se lanza al final si contexto.isHasErrors() — no es un
            // fallo del validador sino una señal de que ya hay errores acumulados; siempre
            // extraemos los mensajes del contexto independientemente de esta excepción.
            try (InputStream xmlStream = new FileInputStream(xmlFile)) {
                ValidacionDuplicadosAts validacionDup =
                    new ValidacionDuplicadosAts(xmlStream, OrigenValidacion.DIMM);
                inyectarDataSource(validacionDup, "ds", hsqlDataSource);
                validacionDup.validar(args);
            } catch (ec.gob.sri.anexo.exceptions.ExcepcionErroresExcedidos ignorada) {
                // Normal: el validador de duplicados lanza esto cuando el contexto tiene errores.
                // Los errores ya están en 'contexto'; se extraen abajo.
                log.debug("ExcepcionErroresExcedidos del validador de duplicados (esperada): {}", ignorada.getMessage());
            }

            // Extraer errores y advertencias del contexto (siempre, incluso con ExcepcionErroresExcedidos)
            extraerMensajes(contexto, response);

        } catch (Exception e) {
            log.warn("No se pudo ejecutar validación de negocio SRI: {}", e.getMessage());
            log.debug("Detalle:", e);
            response.getAdvertencias().add(
                "Validación de negocio no disponible: " + e.getMessage()
            );
        } finally {
            cerrarConexion(conn);
        }
    }

    private void extraerMensajes(ContextoErrores contexto, ValidationResponse response) {
        Map<Object, ErrorDetalleAnexoValidacion> mapaErrores = contexto.getErroresAcumulados();
        if (mapaErrores != null) {
            for (ErrorDetalleAnexoValidacion detalle : mapaErrores.values()) {
                if (detalle.getListaErrores() != null) {
                    for (ErrorValidacion ev : detalle.getListaErrores()) {
                        response.getErrores().add(ev.getDescripcion());
                    }
                }
                if (detalle.getListaWarnings() != null) {
                    for (WarningValidacion wv : detalle.getListaWarnings()) {
                        response.getAdvertencias().add(wv.getDescripcion());
                    }
                }
            }
        }
        if (contexto.getWarningsAcumulados() != null) {
            for (WarningValidacion wv : contexto.getWarningsAcumulados()) {
                response.getAdvertencias().add(wv.getDescripcion());
            }
        }
    }

    // ── Etapa 3: Generación del talón HTML ───────────────────────────────

    private String generarTalon(File xmlFile) {
        Connection conn = null;
        try (InputStream xmlStream = new FileInputStream(xmlFile)) {
            conn = hsqlDataSource.getConnection();

            // GeneradorTalonServicio sobrescribe obtenerPropiedadesTalon()
            // para cargar las plantillas HTML desde el classpath del servicio
            GeneradorTalonServicio generator = new GeneradorTalonServicio(xmlStream);

            // "DIMM" = cargar plantillas del modo local (no del servidor SRI)
            String html = generator.generar(conn, "DIMM");
            if (html != null && html.length() > 100) {
                return incrustarRecursos(html);
            }
        } catch (Exception e) {
            log.warn("No se pudo generar el talón HTML: {}", e.getMessage());
            log.debug("Detalle:", e);
        } finally {
            cerrarConexion(conn);
        }
        return null;
    }

    /**
     * Reemplaza las referencias externas del talón HTML por recursos embebidos:
     *  - <link href="recursos/talonAts.css">  →  <style>...</style> inline
     *  - <img src="recursos/sriTalonLogo.jpg"> →  <img src="data:image/jpeg;base64,...">
     */
    private String incrustarRecursos(String html) {
        // 1. Incrustar CSS
        try (InputStream cssStream = getClass().getResourceAsStream("/talon/talonAts.css")) {
            if (cssStream != null) {
                String css = new String(cssStream.readAllBytes(), StandardCharsets.ISO_8859_1);
                String linkTag = "<link rel=\"stylesheet\" type=\"text/css\" href=\"recursos/talonAts.css\">";
                String styleTag = "<style type=\"text/css\">" + css + "</style>";
                html = html.replace(linkTag, styleTag);
            }
        } catch (Exception e) {
            log.debug("No se pudo incrustar CSS: {}", e.getMessage());
        }

        // 2. Incrustar logo como base64
        try (InputStream imgStream = getClass().getResourceAsStream("/talon/sriTalonLogo.jpg")) {
            if (imgStream != null) {
                String base64 = Base64.getEncoder().encodeToString(imgStream.readAllBytes());
                String dataUri = "data:image/jpeg;base64," + base64;
                html = html.replace("src=\"recursos/sriTalonLogo.jpg\"", "src=\"" + dataUri + "\"");
            }
        } catch (Exception e) {
            log.debug("No se pudo incrustar logo: {}", e.getMessage());
        }

        return html;
    }

    private String extraerLogoATemporal() {
        try {
            try (InputStream logoStream = getClass().getResourceAsStream("/talon/sriTalonLogo.jpg")) {
                if (logoStream == null) return "";
                File logoFile = Files.createTempFile("sri-logo-", ".jpg").toFile();
                logoFile.deleteOnExit();
                java.nio.file.Files.copy(logoStream, logoFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return logoFile.getAbsolutePath();
            }
        } catch (Exception e) {
            log.debug("No se pudo extraer el logo SRI: {}", e.getMessage());
            return "";
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────

    private File escribirArchivoTemporal(String xmlContent) throws IOException {
        File tmp = Files.createTempFile("ats-", ".xml").toFile();
        try (FileWriter fw = new FileWriter(tmp, StandardCharsets.UTF_8)) {
            fw.write(xmlContent);
        }
        return tmp;
    }

    /**
     * Lee el año y mes del XML para configurar el ContextoErrores.
     * Retorna {anio, mes} o {0, 0} si no puede leer.
     */
    private int[] leerPeriodoDelXml(File xmlFile) {
        try {
            String content = new String(Files.readAllBytes(xmlFile.toPath()), StandardCharsets.UTF_8);
            int anio = extraerEnteroEntre(content, "<Anio>", "</Anio>");
            int mes  = extraerEnteroEntre(content, "<Mes>", "</Mes>");
            return new int[]{anio, mes};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    private int extraerEnteroEntre(String content, String inicio, String fin) {
        int i = content.indexOf(inicio);
        int f = content.indexOf(fin);
        if (i < 0 || f < 0) return 0;
        try {
            return Integer.parseInt(content.substring(i + inicio.length(), f).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Inyecta el DataSource en un campo privado de los validadores SRI
     * que no exponen setter (los campos son inyectados por el contenedor EJB
     * en el entorno original del DIMM).
     */
    private void inyectarDataSource(Object target, String fieldName, DataSource ds) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, ds);
        } catch (NoSuchFieldException e) {
            log.debug("Campo '{}' no encontrado en {}", fieldName, target.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("No se pudo inyectar DataSource en campo '{}': {}", fieldName, e.getMessage());
        }
    }

    private void cerrarConexion(Connection conn) {
        if (conn != null) {
            try { conn.close(); } catch (Exception ignored) {}
        }
    }
}
