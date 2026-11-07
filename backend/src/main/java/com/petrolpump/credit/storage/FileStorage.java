package com.petrolpump.credit.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Private file store for bills, payment proofs and database backups. Files are never served directly: callers
 * fetch them through an API that checks the user can see the parent record.
 * STORAGE_TYPE=LOCAL keeps them under STORAGE_LOCAL_ROOT; STORAGE_TYPE=S3 puts them in a private bucket
 * (encrypted at rest) using the instance's IAM role, so no access keys live on the server.
 */
@Service
public class FileStorage {

    public record StoredFile(String storageKey, String originalName, String contentType, long sizeBytes, String sha256) {}

    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final Path root;
    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public FileStorage(@Value("${app.storage.type:LOCAL}") String type,
                       @Value("${app.storage.local-root}") String root,
                       @Value("${app.storage.s3-bucket:}") String bucket,
                       @Value("${app.storage.s3-region:ap-south-1}") String region,
                       @Value("${app.storage.s3-prefix:}") String prefix) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        if ("S3".equalsIgnoreCase(type)) {
            if (bucket.isBlank()) throw new IllegalStateException("STORAGE_TYPE=S3 needs app.storage.s3-bucket (S3_BUCKET)");
            this.s3 = S3Client.builder().region(Region.of(region)).build();
            this.bucket = bucket;
            this.prefix = prefix.isBlank() || prefix.endsWith("/") ? prefix : prefix + "/";
        } else {
            this.s3 = null;
            this.bucket = null;
            this.prefix = "";
        }
    }

    public boolean isS3() {
        return s3 != null;
    }

    public StoredFile store(String folder, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attach a photo or PDF");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is larger than 10 MB");
        }
        try {
            byte[] bytes = file.getBytes();
            // Trust the bytes, not the browser-supplied content type.
            String type = sniff(bytes);
            String ext = switch (type) {
                case "image/jpeg" -> "jpg";
                case "image/png" -> "png";
                case "image/webp" -> "webp";
                case "application/pdf" -> "pdf";
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Only JPG, PNG, WEBP or PDF files are accepted");
            };
            LocalDate d = LocalDate.now();
            String key = "%s/%d/%02d/%s.%s".formatted(folder, d.getYear(), d.getMonthValue(), UUID.randomUUID(), ext);
            if (s3 != null) {
                s3.putObject(b -> b.bucket(bucket).key(prefix + key).contentType(type)
                        .serverSideEncryption(ServerSideEncryption.AES256), RequestBody.fromBytes(bytes));
            } else {
                Path target = resolve(key);
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
            }
            return new StoredFile(key, file.getOriginalFilename(), type, bytes.length, sha256(bytes));
        } catch (IOException e) {
            throw new IllegalStateException("Could not store file", e);
        }
    }

    public byte[] read(String storageKey) {
        try {
            if (s3 != null) return s3.getObjectAsBytes(b -> b.bucket(bucket).key(prefix + storageKey)).asByteArray();
            return Files.readAllBytes(resolve(storageKey));
        } catch (IOException | NoSuchKeyException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
    }

    /** Copies a finished file (a database backup) to S3. No-op on local storage, where it already sits on disk. */
    public void upload(String key, Path file) {
        if (s3 == null) return;
        s3.putObject(b -> b.bucket(bucket).key(prefix + key).serverSideEncryption(ServerSideEncryption.AES256),
                RequestBody.fromFile(file));
    }

    /** Deletes a file written in a transaction that later rolled back, so no orphan is left behind. */
    public void discard(String storageKey) {
        try {
            if (s3 != null) s3.deleteObject(b -> b.bucket(bucket).key(prefix + storageKey));
            else Files.deleteIfExists(resolve(storageKey));
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Bad storage key");
        return p;
    }

    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "application/pdf";
        return "unknown";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
