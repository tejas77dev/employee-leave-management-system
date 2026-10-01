package com.company.leavemanager.repository;

import com.company.leavemanager.domain.AuditAction;
import com.company.leavemanager.domain.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    /**
     * The activity feed, newest first, optionally narrowed by action or by a
     * search across the summary and the actor's name.
     */
    @Query("""
            select a from AuditLog a
            where (:action is null or a.action = :action)
              and (:query is null
                   or lower(a.summary) like concat('%', lower(:query), '%')
                   or lower(a.actorName) like concat('%', lower(:query), '%'))
            order by a.createdAt desc
            """)
    List<AuditLog> search(@Param("action") AuditAction action,
                          @Param("query") String query,
                          Pageable pageable);

    long countByCreatedAtGreaterThanEqual(Instant since);

    @Query("select distinct a.actorName from AuditLog a order by a.actorName asc")
    List<String> findDistinctActorNames();

    /** Row count for the same filters as {@link #search}, for the pager. */
    @Query("""
            select count(a) from AuditLog a
            where (:action is null or a.action = :action)
              and (:query is null
                   or lower(a.summary) like concat('%', lower(:query), '%')
                   or lower(a.actorName) like concat('%', lower(:query), '%'))
            """)
    long countMatching(@Param("action") AuditAction action, @Param("query") String query);
}
