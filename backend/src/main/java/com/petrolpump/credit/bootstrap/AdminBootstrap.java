package com.petrolpump.credit.bootstrap;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.audit.AuditService.AuditEvent;
import com.petrolpump.credit.config.AppProperties;
import com.petrolpump.credit.user.Role;
import com.petrolpump.credit.user.RoleRepository;
import com.petrolpump.credit.user.User;
import com.petrolpump.credit.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Creates the first ADMIN user on an empty database. Does nothing once any admin exists. */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final AppProperties props;

    public AdminBootstrap(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
                          AuditService audit, AppProperties props) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByRole_Code(Role.ADMIN)) return;

        Role admin = roles.findByCode(Role.ADMIN).orElseThrow();
        User user = new User(props.bootstrap().adminUsername(), "System Administrator",
                passwordEncoder.encode(props.bootstrap().adminPassword()), admin);
        users.save(user);
        audit.record(new AuditEvent(null, null, "USER_CREATED", "USER", user.getId().toString(), null,
                Map.of("username", user.getUsername(), "role", Role.ADMIN), "Initial admin bootstrap", null, null));
        log.warn("Created initial admin user '{}'. Change its password after first login.", user.getUsername());
    }
}
