package com.company.librarymanager.repository;

import com.company.librarymanager.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** ADMIN first, then LIBRARIAN, then MEMBER, alphabetical within each. */
    List<User> findAllByOrderByRoleDescNameAsc();

    List<User> findAllByActiveTrueOrderByNameAsc();

    /**
     * Every active user except the one named, used when deciding whether a
     * demotion or deactivation would leave the system with no administrator.
     */
    @Query("""
            select count(u) from User u
            where u.role = com.company.librarymanager.domain.Role.ADMIN
              and u.active = true
              and u.id <> :id
            """)
    long countOtherActiveAdmins(@Param("id") String id);

    long countByActiveTrue();
}
