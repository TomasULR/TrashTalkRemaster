package org.nvias.trashtalk.files;

import org.nvias.trashtalk.auth.UserRepository;
import org.nvias.trashtalk.domain.Attachment;
import org.nvias.trashtalk.domain.Message;
import org.nvias.trashtalk.domain.Server;
import org.nvias.trashtalk.domain.User;
import org.nvias.trashtalk.server.ServerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024; // 100 MB per file

    private final StorageService storage;
    private final StorageQuotaService quota;
    private final AttachmentRepository attachments;
    private final ServerRepository servers;
    private final UserRepository users;

    public FileService(StorageService storage, StorageQuotaService quota,
                       AttachmentRepository attachments, ServerRepository servers,
                       UserRepository users) {
        this.storage     = storage;
        this.quota       = quota;
        this.attachments = attachments;
        this.servers     = servers;
        this.users       = users;
    }

    /** Upload a file for a server (not yet linked to a message). Returns saved Attachment. */
    @Transactional
    public Attachment upload(UUID uploaderId, UUID serverId, MultipartFile file) {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        long size = file.getSize();
        if (size > MAX_FILE_SIZE)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File exceeds 100 MB limit");

        Server server = servers.findById(serverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Server not found"));
        User uploader = users.findById(uploaderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        quota.reserve(serverId, size);

        String objectKey = serverId + "/" + UUID.randomUUID();
        String sha256;
        try {
            sha256 = uploadToMinioWithChecksum(file, objectKey);
        } catch (Exception e) {
            quota.release(serverId, size);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Upload failed: " + e.getMessage());
        }

        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        Attachment att = new Attachment(server, uploader,
                sanitizeFilename(file.getOriginalFilename()), size, contentType, objectKey, sha256);
        att.setUploadComplete(true);
        return attachments.save(att);
    }

    /** Link a previously uploaded attachment to a message. */
    @Transactional
    public void linkToMessage(UUID attachmentId, Message message, UUID requesterId) {
        Attachment att = attachments.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
        if (!att.getUploader().getId().equals(requesterId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your attachment");
        if (att.getMessage() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Attachment already linked");
        att.setMessage(message);
        attachments.save(att);
    }

    /** Open a download stream; caller is responsible for closing it. */
    public InputStream download(UUID attachmentId) {
        Attachment att = attachments.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
        if (!att.isUploadComplete())
            throw new ResponseStatusException(HttpStatus.ACCEPTED, "Upload still in progress");
        return storage.get(att.getStorageKey());
    }

    /** Open a range download stream. */
    public InputStream downloadRange(UUID attachmentId, long offset, long length) {
        Attachment att = attachments.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
        if (!att.isUploadComplete())
            throw new ResponseStatusException(HttpStatus.ACCEPTED, "Upload still in progress");
        return storage.getRange(att.getStorageKey(), offset, length);
    }

    public Attachment getInfo(UUID attachmentId) {
        return attachments.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
    }

    @Transactional
    public void delete(UUID attachmentId, UUID requesterId) {
        Attachment att = attachments.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
        if (!att.getUploader().getId().equals(requesterId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your attachment");
        storage.delete(att.getStorageKey());
        quota.release(att.getServer().getId(), att.getSizeBytes());
        attachments.delete(att);
    }

    private String uploadToMinioWithChecksum(MultipartFile file, String objectKey) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream raw = file.getInputStream();
             DigestInputStream dis = new DigestInputStream(raw, digest)) {
            storage.put(objectKey, dis, file.getSize(),
                    file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "file";
        return name.replaceAll("[/\\\\:*?\"<>|]", "_").strip();
    }
}
