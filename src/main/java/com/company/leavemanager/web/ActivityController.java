package com.company.leavemanager.web;

import com.company.leavemanager.domain.AuditAction;
import com.company.leavemanager.repository.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.time.Instant;

/** The HR activity trail. */
@Controller
public class ActivityController {

    private static final int PAGE_SIZE = 50;
    private static final Duration RECENT_WINDOW = Duration.ofDays(7);

    private final AuditLogRepository auditLogRepository;

    public ActivityController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping("/admin/audit")
    public String activity(@RequestParam(required = false) String action,
                           @RequestParam(required = false) String q,
                           Model model) {
        AuditAction actionFilter = parseAction(action);
        String query = (q == null || q.isBlank()) ? null : q.trim();
        // Evaluated per request, not at render time, so "last 7 days" always
        // means the last 7 days rather than the last 7 days after startup.
        Instant since = Instant.now().minus(RECENT_WINDOW);

        var entries = auditLogRepository.search(actionFilter, query, PageRequest.of(0, PAGE_SIZE));

        model.addAttribute("entries", entries);
        model.addAttribute("action", actionFilter);
        model.addAttribute("query", query);
        model.addAttribute("actions", AuditAction.values());
        model.addAttribute("total", auditLogRepository.countMatching(actionFilter, query));
        // The recent count deliberately ignores the filters, so it reads as
        // "how busy has this been" rather than another filtered number.
        model.addAttribute("recentCount", auditLogRepository.countByCreatedAtGreaterThanEqual(since));
        model.addAttribute("actors", auditLogRepository.findDistinctActorNames());
        model.addAttribute("pageSize", PAGE_SIZE);
        return "admin/audit";
    }

    private AuditAction parseAction(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AuditAction.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
