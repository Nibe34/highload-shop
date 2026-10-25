package ua.highload.shop.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ua.highload.shop.api.Dtos.LoginRequest;
import ua.highload.shop.api.Dtos.LoginResponse;
import ua.highload.shop.api.Dtos.RegisterRequest;
import ua.highload.shop.domain.User;
import ua.highload.shop.service.AuthService;
import ua.highload.shop.service.UserService;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService users;
    private final AuthService auth;

    public UserController(UserService users, AuthService auth) {
        this.users = users;
        this.auth = auth;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public User register(@Valid @RequestBody RegisterRequest req) {
        return users.register(req.email(), req.fullName(), req.region(), req.password());
    }

    @GetMapping("/users/me")
    public User me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return users.own(auth.requireUser(authorization));
    }

    @GetMapping("/users/{id}")
    public User get(@PathVariable long id) {
        return users.get(id);
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        var session = auth.login(req.email(), req.password());
        return new LoginResponse(session.token(), session.userId());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.requireUser(authorization);
        auth.logout(authorization.substring(7).trim());
    }
}
