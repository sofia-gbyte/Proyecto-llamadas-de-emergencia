package cl.apppolicial.repositorio;

import cl.apppolicial.modelo.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
    Optional<Usuario> findByNombreUsuario(String nombreUsuario);
    boolean existsByNombreUsuario(String nombreUsuario);
    Optional<Usuario> findByCorreoIgnoreCase(String correo);
    boolean existsByCorreoIgnoreCase(String correo);
    long count();
}
