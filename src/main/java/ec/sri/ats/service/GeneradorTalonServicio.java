package ec.sri.ats.service;

import ec.gob.sri.anexo.ats.persistencia.talon.GeneradorTalonAnexoAts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;

/**
 * Subclase de {@link GeneradorTalonAnexoAts} que carga las plantillas HTML
 * del talón desde el classpath del servicio en lugar de buscarlas en la
 * instalación local del DIMM.
 *
 * {@code GeneradorTalonAnexoAts.obtenerPropiedadesTalon()} es protected,
 * lo que permite sobreescribirlo sin modificar el código del SRI.
 */
public class GeneradorTalonServicio extends GeneradorTalonAnexoAts {

    private static final Logger log = LoggerFactory.getLogger(GeneradorTalonServicio.class);

    public GeneradorTalonServicio(InputStream xmlInputStream) {
        super(xmlInputStream);
    }

    /**
     * Carga el archivo {@code IVA_talonResumen.properties} desde el classpath
     * (incluido en el JAR bajo {@code /talon/}).
     */
    @Override
    protected Properties obtenerPropiedadesTalon() {
        Properties props = new Properties();
        try (InputStream is = getClass().getResourceAsStream("/talon/IVA_talonResumen.properties")) {
            if (is != null) {
                props.load(new InputStreamReader(is, "ISO-8859-1"));
                log.debug("Plantillas del talón cargadas desde classpath ({} entradas)", props.size());
            } else {
                log.warn("No se encontró /talon/IVA_talonResumen.properties en el classpath");
            }
        } catch (Exception e) {
            log.warn("Error al cargar plantillas del talón: {}", e.getMessage());
        }
        return props;
    }
}
