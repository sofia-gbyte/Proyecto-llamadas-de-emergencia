package cl.apppolicial.modelo;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre_usuario", length = 50, unique = true, nullable = false)
    private String nombreUsuario;

    @Column(name = "password_hash", length = 200, nullable = false)
    private String passwordHash;

    // operador / supervisor / administrador
    @Column(length = 20, nullable = false)
    private String rol = "operador";

    @Column(length = 80)
    private String nombre;

    @Column(length = 80)
    private String apellido;

    @Column(length = 150, unique = true)
    private String correo;

    @Column(length = 30)
    private String institucion;

    private boolean activo = true;

    @Column(name = "fecha_creacion")
    private LocalDateTime fechaCreacion = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNombreUsuario() { return nombreUsuario; }
    public void setNombreUsuario(String v) { this.nombreUsuario = v; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String v) { this.passwordHash = v; }

    public String getRol() { return rol; }
    public void setRol(String v) { this.rol = v; }

    public String getNombre() { return nombre; }
    public void setNombre(String v) { this.nombre = v; }

    public String getApellido() { return apellido; }
    public void setApellido(String v) { this.apellido = v; }

    public String getCorreo() { return correo; }
    public void setCorreo(String v) { this.correo = v; }

    public String getInstitucion() { return institucion; }
    public void setInstitucion(String v) { this.institucion = v; }

    public boolean isActivo() { return activo; }
    public void setActivo(boolean v) { this.activo = v; }

    public LocalDateTime getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(LocalDateTime v) { this.fechaCreacion = v; }
}
