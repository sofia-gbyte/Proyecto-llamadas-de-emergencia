package cl.apppolicial.servicio;

import cl.apppolicial.modelo.Usuario;
import cl.apppolicial.repositorio.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Crea la primera cuenta de administrador SOLO si la base de datos no
 * tiene ningún usuario todavía, y SOLO a partir de variables de entorno
 * (APP_POLICIAL_ADMIN_USER / APP_POLICIAL_ADMIN_PASSWORD).
 *
 * Deliberadamente NO existe un usuario/contraseña por defecto tipo
 * "admin/admin" — es una de las causas más comunes de brechas reales
 * en sistemas que se despliegan y nunca se les cambia la credencial
 * de fábrica. Si no se definen esas variables, la API arranca igual,
 * pero nadie puede loguearse hasta que un administrador las configure
 * y reinicie el servicio.
 */
@Component
public class AdminSeederRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeederRunner.class);

    private final UsuarioRepository repo;
    private final SeguridadService seguridad;
    private final String adminUsuario;
    private final String adminPassword;

    public AdminSeederRunner(
            UsuarioRepository repo,
            SeguridadService seguridad,
            @Value("${app.admin-bootstrap-user:}") String adminUsuario,
            @Value("${app.admin-bootstrap-password:}") String adminPassword
    ) {
        this.repo = repo;
        this.seguridad = seguridad;
        this.adminUsuario = adminUsuario;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (repo.count() > 0) {
            return; // ya existen usuarios, no tocar nada
        }

        if (adminUsuario.isBlank() || adminPassword.isBlank()) {
            log.warn("========================================================================");
            log.warn("No hay ningún usuario registrado y no se definieron las variables de");
            log.warn("entorno APP_POLICIAL_ADMIN_USER y APP_POLICIAL_ADMIN_PASSWORD.");
            log.warn("Nadie podrá iniciar sesión hasta que definas esas variables y reinicies.");
            log.warn("========================================================================");
            return;
        }

        if (adminPassword.length() < 12) {
            log.warn("La contraseña de administrador inicial tiene menos de 12 caracteres. " +
                      "Se recomienda usar una más larga (ideal: frase de al menos 16 caracteres).");
        }

        Usuario admin = new Usuario();
        admin.setNombreUsuario(adminUsuario);
        admin.setPasswordHash(seguridad.hashPassword(adminPassword));
        admin.setRol("administrador");
        admin.setActivo(true);
        admin.setNombre("Administrador");
        admin.setApellido("CODES");
        admin.setCorreo(adminUsuario.contains("@") ? adminUsuario : null);
        repo.save(admin);

        log.info("Usuario administrador inicial '{}' creado a partir de variables de entorno. " +
                  "Considera quitar APP_POLICIAL_ADMIN_PASSWORD del entorno una vez confirmado el login.",
                  adminUsuario);
    }
}
