package com.nomi.wayfinder.config;

import com.nomi.wayfinder.entity.UserRole;
import com.nomi.wayfinder.repository.UserRepository;
import com.nomi.wayfinder.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// Creates the first ADMIN from ADMIN_EMAIL / ADMIN_PASSWORD so places can be managed through the API
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final NomiProperties.Security properties;
    private final UserRepository userRepository;
    private final UserService userService;

    public AdminBootstrap(NomiProperties properties, UserRepository userRepository, UserService userService) {
        this.properties = properties.security();
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = properties.adminEmail();
        String password = properties.adminPassword();

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            return;
        }

        userService.createUser(email, password, "Admin", UserRole.ADMIN);
        log.info("Created admin user {}", email);
    }
}
