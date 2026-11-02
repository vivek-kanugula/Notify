package com.notify.admin.bootstrap;

import com.notify.admin.entity.AdminUserEntity;
import com.notify.admin.repository.AdminUserRepository;
import com.notify.common.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * First-boot administrator seeding component.
 * If no admin users exist, creates the initial platform administrator from configuration.
 * Password is BCrypt(12) hashed before storage.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final String defaultEmail;
    private final String defaultPassword;

    public AdminBootstrap(
            AdminUserRepository adminUserRepository,
            PasswordEncoder passwordEncoder,
            @Value("${notify.admin.default-email:admin@notify.local}") String defaultEmail,
            @Value("${notify.admin.default-password:placeholder_admin_password_min_10_chars}") String defaultPassword
    ) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.defaultEmail = defaultEmail;
        this.defaultPassword = defaultPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminUserRepository.count() == 0) {
            log.info("Seeding initial platform administrator: {}", defaultEmail);
            AdminUserEntity admin = new AdminUserEntity();
            admin.setEmail(defaultEmail.toLowerCase(Locale.ROOT));
            admin.setPasswordHash(passwordEncoder.encode(defaultPassword));
            admin.setRole(Role.PLATFORM_ADMIN);
            try {
                adminUserRepository.save(admin);
                log.info("Platform administrator seeded successfully");
            } catch (DataIntegrityViolationException e) {
                log.info("Platform administrator already seeded by concurrent instance");
            }
        }
    }
}

