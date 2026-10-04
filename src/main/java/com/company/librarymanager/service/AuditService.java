package com.company.librarymanager.service;

import com.company.librarymanager.domain.AuditAction;
import com.company.librarymanager.domain.AuditLog;
import com.company.librarymanager.domain.User;
import com.company.librarymanager.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the append-only activity trail.
 *
 * <p>Every method uses {@code MANDATORY} propagation, so an audit row can
 * never be written on its own: it either joins the caller's transaction and
 * commits with the change it describes, or the whole thing rolls back. A
 * half-recorded approval is worse than no record at all.
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditLog record(User actor,
                           AuditAction action,
                           String entityType,
                           String entityId,
                           String summary,
                           Map<String, Object> metadata) {
        AuditLog entry = new AuditLog();
        entry.setId(UUID.randomUUID().toString());
        // The name is stored rather than joined at render time, so the trail
        // still reads correctly once the account is gone.
        entry.setActor(actor);
        entry.setActorName(actor != null ? actor.getName() : "System");
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setSummary(summary);
        entry.setMetadata(toJson(metadata));
        return auditLogRepository.save(entry);
    }

    /** An empty change list, for callers that build one conditionally. */
    public static Map<String, Object> changes() {
        return new LinkedHashMap<>();
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            // Losing detail in the trail is not worth failing the operation.
            return null;
        }
    }
}
