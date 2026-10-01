package com.company.leavemanager.repository;

import com.company.leavemanager.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    /**
     * Locks the employee row for the rest of the transaction.
     *
     * <p>Used to serialise leave submissions by one employee. Two overlapping
     * requests can use different leave types, and therefore contend on
     * different balance rows, so locking a balance alone would not stop both
     * from passing the overlap check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockUser(@Param("id") String id);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<User> findAllByOrderByRoleDescNameAsc();

    List<User> findAllByActiveTrueOrderByNameAsc();

    /**
     * Every active user except the one named, used when deciding whether a
     * demotion or deactivation would leave the system with no HR.
     */
    @Query("""
            select count(u) from User u
            where u.role = com.company.leavemanager.domain.Role.HR
              and u.active = true
              and u.id <> :id
            """)
    long countOtherActiveHr(@Param("id") String id);

    long countByActiveTrue();
}
