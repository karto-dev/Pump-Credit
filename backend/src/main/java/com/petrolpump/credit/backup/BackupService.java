package com.petrolpump.credit.backup;

import com.petrolpump.credit.credit.CreditService;
import com.petrolpump.credit.notification.NotificationService;
import com.petrolpump.credit.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * pg_dump (custom format) into BACKUP_DIR, copied to S3 when storage is S3, local copies pruned after
 * BACKUP_RETENTION_DAYS. BACKUP_COMMAND overrides the dump command (it must write the dump to stdout), e.g.
 * "docker exec ppcms-postgres pg_dump -U ppcms --format=custom petrol_credit" on a laptop without pg_dump.
 * Restore: pg_restore --clean --no-owner -d petrol_credit <file>.
 */
@Service
public class BackupService {

    public record BackupRow(long id, OffsetDateTime startedAt, OffsetDateTime finishedAt, String status, String location,
                            Long sizeBytes, String triggeredByName, String error) {}

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final CreditService credit;
    private final NotificationService notify;
    private final Path dir;
    private final String command;
    private final boolean scheduled;
    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;

    public BackupService(JdbcTemplate jdbc, FileStorage storage, CreditService credit, NotificationService notify,
                         @Value("${app.backup.dir:./backups}") String dir,
                         @Value("${app.backup.command:}") String command,
                         @Value("${app.backup.enabled:false}") boolean scheduled,
                         @Value("${spring.datasource.url}") String dbUrl,
                         @Value("${spring.datasource.username}") String dbUser,
                         @Value("${spring.datasource.password}") String dbPassword) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.credit = credit;
        this.notify = notify;
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        this.command = command;
        this.scheduled = scheduled;
        this.dbUrl = dbUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
    }

    public List<BackupRow> list() {
        return jdbc.query("""
                SELECT b.id, b.started_at, b.finished_at, b.status, b.location, b.size_bytes,
                       u.full_name AS triggered_by_name, b.error
                  FROM backup_runs b LEFT JOIN users u ON u.id = b.triggered_by
                 ORDER BY b.started_at DESC LIMIT 100
                """, new DataClassRowMapper<>(BackupRow.class));
    }

    @Scheduled(cron = "${app.backup.cron:0 30 2 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        if (!scheduled) return;
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_lock(72003)", Boolean.class))) return;
        try {
            run(null);
        } catch (RuntimeException e) {
            log.error("Nightly backup failed", e);
        } finally {
            jdbc.queryForObject("SELECT pg_advisory_unlock(72003)", Boolean.class);
        }
    }

    /** Runs synchronously; a small pump database dumps in seconds. */
    public BackupRow run(Long triggeredBy) {
        Integer running = jdbc.queryForObject(
                "SELECT COUNT(*) FROM backup_runs WHERE status = 'RUNNING' AND started_at > now() - interval '1 hour'", Integer.class);
        if (running != null && running > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "A backup is already running");
        long id = jdbc.queryForObject("INSERT INTO backup_runs (status, triggered_by) VALUES ('RUNNING', ?) RETURNING id",
                Long.class, triggeredBy);
        String name = "petrol_credit-" + STAMP.format(LocalDateTime.now(ZoneId.of("Asia/Kolkata"))) + ".dump";
        Path file = dir.resolve(name);
        try {
            Files.createDirectories(dir);
            ProcessBuilder pb = new ProcessBuilder(dumpCommand()).redirectOutput(file.toFile());
            pb.environment().put("PGPASSWORD", dbPassword);
            Process p = pb.start();
            String err;
            try (var in = p.getErrorStream()) {
                if (!p.waitFor(30, TimeUnit.MINUTES)) {
                    p.destroyForcibly();
                    throw new IOException("pg_dump did not finish in 30 minutes");
                }
                err = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            long size = Files.exists(file) ? Files.size(file) : 0;
            if (p.exitValue() != 0 || size == 0) {
                throw new IOException("Dump failed (exit " + p.exitValue() + ")" + (err.isEmpty() ? "" : ": " + err));
            }
            String location = file.toString();
            if (storage.isS3()) {
                storage.upload("backups/" + name, file);
                location = "s3: backups/" + name + " (local copy " + file + ")";
            }
            jdbc.update("UPDATE backup_runs SET status = 'SUCCESS', finished_at = now(), location = ?, size_bytes = ? WHERE id = ?",
                    location, size, id);
            prune();
            log.info("Backup {} written ({} bytes)", name, size);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg.contains("Cannot run program")) msg = "pg_dump is not installed on the server. Set BACKUP_COMMAND. " + msg;
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // nothing more to clean up
            }
            jdbc.update("UPDATE backup_runs SET status = 'FAILED', finished_at = now(), error = ? WHERE id = ?",
                    msg.length() > 1000 ? msg.substring(0, 1000) : msg, id);
            notify.toPermission("BACKUP_MANAGE", "BACKUP_FAILED", Map.of("error", msg.length() > 200 ? msg.substring(0, 200) : msg),
                    "BACKUP", id);
            log.error("Backup {} failed: {}", id, msg);
        }
        return list().stream().filter(b -> b.id() == id).findFirst().orElseThrow();
    }

    /** The local dump file of a successful run, for download by an admin. */
    public Path file(long id) {
        String location = jdbc.query("SELECT location FROM backup_runs WHERE id = ? AND status = 'SUCCESS'",
                        (rs, i) -> rs.getString(1), id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Backup not found"));
        String name = location.replaceAll("^.*?(petrol_credit-[0-9-]+\\.dump).*$", "$1");
        Path p = dir.resolve(name).normalize();
        if (!p.startsWith(dir) || !Files.exists(p)) {
            throw new ResponseStatusException(HttpStatus.GONE, "The local copy has been pruned" + (storage.isS3() ? "; it is still in S3" : ""));
        }
        return p;
    }

    private List<String> dumpCommand() {
        if (!command.isBlank()) return Arrays.asList(command.trim().split("\\s+"));
        // jdbc:postgresql://host:port/db?params
        URI uri = URI.create(dbUrl.substring("jdbc:".length()));
        List<String> cmd = new ArrayList<>(List.of("pg_dump", "--format=custom", "--no-owner",
                "--host", uri.getHost(), "--username", dbUser, "--dbname", uri.getPath().substring(1)));
        if (uri.getPort() > 0) cmd.addAll(List.of("--port", String.valueOf(uri.getPort())));
        return cmd;
    }

    private void prune() {
        int days = Integer.parseInt(credit.setting("BACKUP_RETENTION_DAYS", "30"));
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().matches("petrol_credit-[0-9-]+\\.dump")).forEach(f -> {
                try {
                    if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) Files.delete(f);
                } catch (IOException e) {
                    log.warn("Could not prune {}: {}", f, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not list backup dir: {}", e.getMessage());
        }
    }
}
