package cl.apppolicial.seguridad;

import cl.apppolicial.modelo.Usuario;
import cl.apppolicial.repositorio.UsuarioRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UsuarioDetailsService implements UserDetailsService {

    private final UsuarioRepository repo;

    public UsuarioDetailsService(UsuarioRepository repo) {
        this.repo = repo;
    }

    @Override
    public UserDetails loadUserByUsername(String nombreUsuario) throws UsernameNotFoundException {
        Usuario u = repo.findByNombreUsuario(nombreUsuario)
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado"));

        return User.builder()
                .username(u.getNombreUsuario())
                .password(u.getPasswordHash())
                .disabled(!u.isActivo())
                .authorities(new SimpleGrantedAuthority("ROLE_" + u.getRol().toUpperCase()))
                .build();
    }
}
