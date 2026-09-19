package cl.apppolicial.controlador.dto;

import cl.apppolicial.modelo.Usuario;

public record UsuarioResponse(
        Long id,
        String nombreUsuario,
        String rol,
        boolean activo,
        String nombreCompleto,
        String correo,
        String institucion
) {
    public static UsuarioResponse de(Usuario u) {
        String completo = ((u.getNombre() == null ? "" : u.getNombre()) + " " +
                (u.getApellido() == null ? "" : u.getApellido())).strip();
        if (completo.isBlank()) completo = u.getNombreUsuario();
        return new UsuarioResponse(u.getId(), u.getNombreUsuario(), u.getRol(), u.isActivo(),
                completo, u.getCorreo(), u.getInstitucion());
    }
}
