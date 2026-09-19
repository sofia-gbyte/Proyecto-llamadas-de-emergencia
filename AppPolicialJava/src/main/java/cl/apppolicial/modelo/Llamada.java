package cl.apppolicial.modelo;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "llamadas")
public class Llamada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fecha_hora")
    private LocalDateTime fechaHora = LocalDateTime.now();

    @Column(name = "audio_original", length = 300)
    private String audioOriginal;

    @Lob
    private String transcripcion;

    @Lob
    @Column(name = "resumen_operativo")
    private String resumenOperativo;

    @Column(length = 20)
    private String prioridad; // URGENTE / ROJA / MEDIA / VERDE

    private int puntajeUrgente;
    private int puntajeRoja;
    private int puntajeMedia;
    private int puntajeVerde;

    @Lob
    @Column(name = "palabras_destacadas")
    private String palabrasDestacadas; // JSON: ["palabra1", "palabra2"]

    @Column(name = "direccion_detectada", length = 200)
    private String direccionDetectada;

    private Double latitud;
    private Double longitud;

    // Gestión del caso
    private boolean asignado = false;

    @Column(name = "operador_asignado", length = 50)
    private String operadorAsignado;

    @Column(name = "fecha_asignacion")
    private LocalDateTime fechaAsignacion;

    @Column(name = "fecha_cierre")
    private LocalDateTime fechaCierre;

    @Lob
    @Column(name = "comentario_cierre")
    private String comentarioCierre;

    // Auditoría
    @Column(name = "ip_origen", length = 45)
    private String ipOrigen;

    @Column(name = "usuario_creacion", length = 50)
    private String usuarioCreacion;

    // --- getters y setters ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getFechaHora() {
        return fechaHora;
    }

    public void setFechaHora(LocalDateTime fechaHora) {
        this.fechaHora = fechaHora;
    }

    public String getAudioOriginal() {
        return audioOriginal;
    }

    public void setAudioOriginal(String audioOriginal) {
        this.audioOriginal = audioOriginal;
    }

    public String getTranscripcion() {
        return transcripcion;
    }

    public void setTranscripcion(String transcripcion) {
        this.transcripcion = transcripcion;
    }

    public String getResumenOperativo() {
        return resumenOperativo;
    }

    public void setResumenOperativo(String resumenOperativo) {
        this.resumenOperativo = resumenOperativo;
    }

    public String getPrioridad() {
        return prioridad;
    }

    public void setPrioridad(String prioridad) {
        this.prioridad = prioridad;
    }

    public int getPuntajeUrgente() {
        return puntajeUrgente;
    }

    public void setPuntajeUrgente(int v) {
        this.puntajeUrgente = v;
    }

    public int getPuntajeRoja() {
        return puntajeRoja;
    }

    public void setPuntajeRoja(int v) {
        this.puntajeRoja = v;
    }

    public int getPuntajeMedia() {
        return puntajeMedia;
    }

    public void setPuntajeMedia(int v) {
        this.puntajeMedia = v;
    }

    public int getPuntajeVerde() {
        return puntajeVerde;
    }

    public void setPuntajeVerde(int v) {
        this.puntajeVerde = v;
    }

    public String getPalabrasDestacadas() {
        return palabrasDestacadas;
    }

    public void setPalabrasDestacadas(String v) {
        this.palabrasDestacadas = v;
    }

    public String getDireccionDetectada() {
        return direccionDetectada;
    }

    public void setDireccionDetectada(String v) {
        this.direccionDetectada = v;
    }

    public Double getLatitud() {
        return latitud;
    }

    public void setLatitud(Double v) {
        this.latitud = v;
    }

    public Double getLongitud() {
        return longitud;
    }

    public void setLongitud(Double v) {
        this.longitud = v;
    }

    public boolean isAsignado() {
        return asignado;
    }

    public void setAsignado(boolean asignado) {
        this.asignado = asignado;
    }

    public String getOperadorAsignado() {
        return operadorAsignado;
    }

    public void setOperadorAsignado(String v) {
        this.operadorAsignado = v;
    }

    public LocalDateTime getFechaAsignacion() {
        return fechaAsignacion;
    }

    public void setFechaAsignacion(LocalDateTime v) {
        this.fechaAsignacion = v;
    }

    public LocalDateTime getFechaCierre() {
        return fechaCierre;
    }

    public void setFechaCierre(LocalDateTime v) {
        this.fechaCierre = v;
    }

    public String getComentarioCierre() {
        return comentarioCierre;
    }

    public void setComentarioCierre(String v) {
        this.comentarioCierre = v;
    }

    public String getIpOrigen() {
        return ipOrigen;
    }

    public void setIpOrigen(String v) {
        this.ipOrigen = v;
    }

    public String getUsuarioCreacion() {
        return usuarioCreacion;
    }

    public void setUsuarioCreacion(String v) {
        this.usuarioCreacion = v;
    }
}
