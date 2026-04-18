package ec.sri.ats.model;

import java.util.ArrayList;
import java.util.List;

public class ValidationResponse {

    private boolean valido;
    private List<String> erroresXsd   = new ArrayList<>();
    private List<String> errores      = new ArrayList<>();
    private List<String> advertencias = new ArrayList<>();
    private String talonHtml;

    // ── getters / setters ─────────────────────────────────────────────────

    public boolean isValido() { return valido; }
    public void setValido(boolean valido) { this.valido = valido; }

    public List<String> getErroresXsd() { return erroresXsd; }
    public void setErroresXsd(List<String> erroresXsd) { this.erroresXsd = erroresXsd; }

    public List<String> getErrores() { return errores; }
    public void setErrores(List<String> errores) { this.errores = errores; }

    public List<String> getAdvertencias() { return advertencias; }
    public void setAdvertencias(List<String> advertencias) { this.advertencias = advertencias; }

    public String getTalonHtml() { return talonHtml; }
    public void setTalonHtml(String talonHtml) { this.talonHtml = talonHtml; }
}
