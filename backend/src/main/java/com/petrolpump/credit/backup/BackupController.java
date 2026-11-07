package com.petrolpump.credit.backup;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.backup.BackupService.BackupRow;
import com.petrolpump.credit.jobs.HousekeepingJobs;
import com.petrolpump.credit.security.CurrentUser;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
public class BackupController {

    private final BackupService backups;
    private final HousekeepingJobs jobs;
    private final AuditService audit;

    public BackupController(BackupService backups, HousekeepingJobs jobs, AuditService audit) {
        this.backups = backups;
        this.jobs = jobs;
        this.audit = audit;
    }

    @GetMapping("/api/backups")
    @PreAuthorize("hasAuthority('BACKUP_MANAGE')")
    public List<BackupRow> list() {
        return backups.list();
    }

    @PostMapping("/api/backups")
    @PreAuthorize("hasAuthority('BACKUP_MANAGE')")
    public BackupRow run(@AuthenticationPrincipal CurrentUser user) {
        BackupRow row = backups.run(user.id());
        audit.log(user, "BACKUP_TRIGGERED", "BACKUP", row.id(), null, Map.of("status", row.status()), null);
        return row;
    }

    @GetMapping("/api/backups/{id}/download")
    @PreAuthorize("hasAuthority('BACKUP_MANAGE')")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        Path file = backups.file(id);
        audit.log(user, "BACKUP_DOWNLOADED", "BACKUP", id, null, null, null);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.getFileName().toString()).build().toString())
                .body(new FileSystemResource(file));
    }

    /** Sends the outstanding reminders now instead of waiting for the daily run. */
    @PostMapping("/api/jobs/reminders")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public Map<String, Integer> reminders(@AuthenticationPrincipal CurrentUser user) {
        int n = jobs.sendReminders();
        audit.log(user, "REMINDERS_TRIGGERED", "JOB", "reminders", null, Map.of("customers", n), null);
        return Map.of("customers", n);
    }
}
