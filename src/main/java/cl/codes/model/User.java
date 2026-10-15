package cl.codes.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", length = 50, unique = true, nullable = false)
    private String username;

    @Column(name = "password_hash", length = 200, nullable = false)
    private String passwordHash;

    @Column(length = 20, nullable = false)
    private String role = "operator";

    @Column(length = 80)
    private String firstName;

    @Column(length = 80)
    private String lastName;

    @Column(length = 150, unique = true)
    private String email;

    @Column(length = 30)
    private String institution;

    private boolean active = true;

    // Cuenta sintética creada para que un ADMINISTRATOR pueda ver la
    // interfaz de operador de una institución ("modo prueba") sin usar
    // credenciales reales. Nunca se loguea con contraseña (el hash es
    // aleatorio e inaccesible) y CallService/LiveCallService le bloquean
    // cualquier escritura sobre llamadas reales: solo puede mirar.
    @Column(name = "test_account", nullable = false, columnDefinition = "boolean default false")
    private boolean testAccount = false;

    // Cualquier JWT emitido ANTES de esta marca deja de aceptarse, aunque no
    // haya expirado todavía. Se actualiza al cerrar sesión, al cambiar la
    // contraseña (propia o por recuperación) o cuando un administrador
    // fuerza el cierre de sesiones de otro usuario. null = sin restricción.
    @Column(name = "sessions_valid_from")
    private LocalDateTime sessionsValidFrom;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getInstitution() { return institution; }
    public void setInstitution(String institution) { this.institution = institution; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isTestAccount() { return testAccount; }
    public void setTestAccount(boolean testAccount) { this.testAccount = testAccount; }

    public LocalDateTime getSessionsValidFrom() { return sessionsValidFrom; }
    public void setSessionsValidFrom(LocalDateTime sessionsValidFrom) { this.sessionsValidFrom = sessionsValidFrom; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}


