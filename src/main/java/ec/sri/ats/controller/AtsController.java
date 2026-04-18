package ec.sri.ats.controller;

import ec.sri.ats.model.ValidationResponse;
import ec.sri.ats.service.AtsValidationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * Endpoint REST para validar un XML ATS del SRI Ecuador.
 *
 * Uso:
 *   POST /api/ats/validar
 *   Content-Type: application/xml  (body = contenido XML)
 *   Content-Type: text/plain       (body = contenido XML)
 *   Content-Type: multipart/form-data  (campo "archivo" = archivo XML)
 *
 * Respuesta JSON:
 *   {
 *     "valido": true/false,
 *     "erroresXsd": [...],
 *     "errores": [...],
 *     "advertencias": [...],
 *     "talonHtml": "<html>...</html>"
 *   }
 */
@RestController
@RequestMapping("/api/ats")
public class AtsController {

    @Autowired
    private AtsValidationService validationService;

    /**
     * Valida un XML ATS enviado como body (text/xml o application/xml o text/plain).
     */
    @PostMapping(
        value = "/validar",
        consumes = { MediaType.APPLICATION_XML_VALUE,
                     MediaType.TEXT_XML_VALUE,
                     MediaType.TEXT_PLAIN_VALUE },
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ValidationResponse> validarXml(@RequestBody String xmlContent) {
        ValidationResponse response = validationService.validar(xmlContent);
        return ResponseEntity.ok(response);
    }

    /**
     * Valida un XML ATS enviado como archivo multipart.
     */
    @PostMapping(
        value = "/validar",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ValidationResponse> validarArchivo(
            @RequestParam("archivo") MultipartFile archivo) throws Exception {
        String xmlContent = new String(archivo.getBytes(), StandardCharsets.UTF_8);
        ValidationResponse response = validationService.validar(xmlContent);
        return ResponseEntity.ok(response);
    }

    /**
     * Health check.
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("{\"status\":\"UP\",\"servicio\":\"ATS Validator SRI Ecuador\"}");
    }
}
