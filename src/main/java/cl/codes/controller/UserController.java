package cl.codes.controller;

import cl.codes.controller.dto.CreateUserRequest;
import cl.codes.controller.dto.UserResponse;
import cl.codes.model.User;
import cl.codes.repository.UserRepository;
import cl.codes.service.SecurityService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class UserController {

    private final UserRepository repo;
    private final SecurityService securityService;

    public UserController(UserRepository repo, SecurityService securityService) {
        this.repo = repo;
        this.securityService = securityService;
    }

    @GetMapping
    public List<UserResponse> list() {
        return repo.findAll().stream().map(UserResponse::de).toList();
    }

    @PostMapping
    public UserResponse create(@Valid @RequestBody CreateUserRequest req) {
        if (repo.existsByUsername(req.username())) {
            throw new IllegalStateException("A user with that username already exists");
        }
        User user = new User();
        user.setUsername(req.username());
        user.setPasswordHash(securityService.hashPassword(req.password()));
        user.setRole(req.rol());
        user.setActive(true);
        return UserResponse.de(repo.save(user));
    }

    @PatchMapping("/{id}/disable")
    public UserResponse disable(@PathVariable Long id, Authentication auth) {
        User user = get(id);
        if (user.getUsername().equals(auth.getName())) {
            throw new IllegalStateException("You cannot disable your own account");
        }
        user.setActive(false);
        return UserResponse.de(repo.save(user));
    }

    @PatchMapping("/{id}/enable")
    public UserResponse enable(@PathVariable Long id) {
        User user = get(id);
        user.setActive(true);
        return UserResponse.de(repo.save(user));
    }

    private User get(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));
    }
}


