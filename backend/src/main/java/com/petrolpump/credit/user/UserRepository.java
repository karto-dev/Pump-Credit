package com.petrolpump.credit.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query("select u from User u where lower(u.username) = lower(?1)")
    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByRole_Code(String roleCode);
}
