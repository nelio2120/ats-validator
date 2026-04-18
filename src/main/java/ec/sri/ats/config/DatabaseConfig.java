package ec.sri.ats.config;

import org.hsqldb.jdbc.JDBCDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);
    private static final String DB_NAME = "bdd_dimm_anexos";

    @Bean
    public DataSource hsqlDataSource() throws IOException {
        Path tmpDir = prepararDirectorioBD();
        String url = "jdbc:hsqldb:file:" + tmpDir.resolve(DB_NAME).toAbsolutePath()
                   + ";readonly=true;shutdown=true";

        log.info("Catálogos SRI cargados desde: {}", tmpDir);

        JDBCDataSource ds = new JDBCDataSource();
        ds.setUrl(url);
        ds.setUser("SA");
        ds.setPassword("");
        return ds;
    }

    private Path prepararDirectorioBD() throws IOException {
        Path tmpDir = Files.createTempDirectory("dimm-catalogs-");
        copiarRecurso("/db/" + DB_NAME + ".script",     tmpDir.resolve(DB_NAME + ".script"));
        copiarRecurso("/db/" + DB_NAME + ".properties", tmpDir.resolve(DB_NAME + ".properties"));
        File logFile = tmpDir.resolve(DB_NAME + ".log").toFile();
        if (!logFile.exists()) logFile.createNewFile();
        return tmpDir;
    }

    private void copiarRecurso(String recurso, Path destino) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(recurso)) {
            if (is == null) throw new IOException("No encontrado en classpath: " + recurso);
            Files.copy(is, destino, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
