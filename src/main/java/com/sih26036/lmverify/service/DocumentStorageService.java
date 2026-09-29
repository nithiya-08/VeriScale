package com.sih26036.lmverify.service;

import com.sih26036.lmverify.entity.StoredDocument;
import com.sih26036.lmverify.exception.ApiException;
import com.sih26036.lmverify.repository.StoredDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentStorageService {

    private static final int MAX_PHOTO_BYTES = 3 * 1024 * 1024;
    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;

    private final StoredDocumentRepository documentRepository;

    @Value("${app.uploads.dir}")
    private String uploadsDir;

    /** Stores a JPEG/PNG sent as a data URL from the phone. */
    public StoredDocument savePhoto(String ownerEntity, Long ownerId, String dataUrl,
                                    Instant capturedAt, Double lat, Double lng) {
        int comma = dataUrl.indexOf(',');
        String header = comma > 0 ? dataUrl.substring(0, comma) : "";
        if (!header.startsWith("data:image/jpeg;base64") && !header.startsWith("data:image/png;base64")) {
            throw ApiException.badRequest("Photos must be JPEG or PNG");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Photo data is corrupted");
        }
        if (bytes.length > MAX_PHOTO_BYTES) {
            throw ApiException.badRequest("Photo is too large (max 3 MB after compression)");
        }
        String ext = header.contains("png") ? ".png" : ".jpg";
        Path dir = Path.of(uploadsDir, ownerEntity.toLowerCase(), String.valueOf(ownerId));
        Path file = dir.resolve(UUID.randomUUID() + ext);
        try {
            Files.createDirectories(dir);
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return documentRepository.save(StoredDocument.builder()
                .ownerEntity(ownerEntity).ownerId(ownerId).type(StoredDocument.Type.PHOTO)
                .path(file.toString()).sha256(sha256(bytes))
                .fileName("photo" + ext).contentType(ext.equals(".png") ? "image/png" : "image/jpeg")
                .label("Inspection photo").sizeBytes((long) bytes.length)
                .capturedAt(capturedAt).gpsLat(lat).gpsLng(lng)
                .build());
    }

    /**
     * Stores an owner-uploaded document (PDF, JPEG or PNG, max 5 MB). The type is decided from the
     * file's first bytes, not from its name or the browser's content type, so a renamed .exe is rejected.
     */
    public StoredDocument saveUpload(String ownerEntity, Long ownerId, MultipartFile upload, String label) {
        if (upload == null || upload.isEmpty()) {
            throw ApiException.badRequest("Choose a file to upload");
        }
        if (upload.getSize() > MAX_UPLOAD_BYTES) {
            throw ApiException.badRequest("File is too large (max 5 MB)");
        }
        byte[] bytes;
        try {
            bytes = upload.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String[] kind = sniff(bytes);
        if (kind == null) {
            throw ApiException.badRequest("Only PDF, JPEG or PNG files are allowed");
        }
        Path dir = Path.of(uploadsDir, ownerEntity.toLowerCase(), String.valueOf(ownerId));
        Path file = dir.resolve(UUID.randomUUID() + kind[1]);
        try {
            Files.createDirectories(dir);
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String original = upload.getOriginalFilename() == null ? "document" + kind[1]
                : Path.of(upload.getOriginalFilename()).getFileName().toString();
        return documentRepository.save(StoredDocument.builder()
                .ownerEntity(ownerEntity).ownerId(ownerId).type(StoredDocument.Type.DOC)
                .path(file.toString()).sha256(sha256(bytes))
                .fileName(original.length() > 120 ? original.substring(original.length() - 120) : original)
                .contentType(kind[0]).label(label == null || label.isBlank() ? "Document" : label.trim())
                .sizeBytes((long) bytes.length)
                .build());
    }

    /** @return {contentType, extension} or null if the bytes are not an allowed type. */
    private static String[] sniff(byte[] b) {
        if (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return new String[]{"application/pdf", ".pdf"};
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return new String[]{"image/jpeg", ".jpg"};
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return new String[]{"image/png", ".png"};
        }
        return null;
    }

    public byte[] read(StoredDocument doc) {
        try {
            return Files.readAllBytes(Path.of(doc.getPath()));
        } catch (IOException e) {
            throw ApiException.notFound("File missing on server");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
