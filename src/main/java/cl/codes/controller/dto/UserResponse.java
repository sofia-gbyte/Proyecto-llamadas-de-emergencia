package cl.codes.controller.dto;

import cl.codes.model.User;

public record UserResponse(
        Long id,
        String username,
        String role,
        boolean active,
        String fullName,
        String email,
        String institution
) {
    public static UserResponse de(User user) {
        String fullName = ((user.getFirstName() == null ? "" : user.getFirstName()) + " " +
                (user.getLastName() == null ? "" : user.getLastName())).strip();
        if (fullName.isBlank()) fullName = user.getUsername();
        return new UserResponse(user.getId(), user.getUsername(), user.getRole(), user.isActive(),
                fullName, user.getEmail(), user.getInstitution());
    }
}


