package com.company.librarymanager.repository;

import com.company.librarymanager.domain.Session;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<Session, String> {

    Optional<Session> findByToken(String token);

    void deleteByToken(String token);

    void deleteByExpiresAtBefore(Instant cutoff);
}
