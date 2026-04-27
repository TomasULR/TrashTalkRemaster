package org.nvias.trashtalk.files;

import org.nvias.trashtalk.domain.Attachment;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

@RestController
@RequestMapping("/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /** POST /files/upload?serverId=... — multipart, returns attachment JSON */
    @PostMapping("/upload")
    public AttachmentDto upload(
            @AuthenticationPrincipal String userId,
            @RequestParam UUID serverId,
            @RequestParam("file") MultipartFile file) {
        Attachment att = fileService.upload(UUID.fromString(userId), serverId, file);
        return AttachmentDto.from(att);
    }

    /** GET /files/{attachmentId} — full download (supports Range header) */
    @GetMapping("/{attachmentId}")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable UUID attachmentId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        Attachment info = fileService.getInfo(attachmentId);
        String filename  = info.getFilename();
        String mimeType  = info.getMimeType() != null ? info.getMimeType() : "application/octet-stream";
        long   totalSize = info.getSizeBytes();

        ContentDisposition cd = ContentDisposition.attachment().filename(filename).build();

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            return serveRange(attachmentId, rangeHeader, mimeType, totalSize, cd);
        }

        InputStream stream = fileService.download(attachmentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(MediaType.parseMediaType(mimeType))
                .contentLength(totalSize)
                .body(new InputStreamResource(stream));
    }

    /** GET /files/{attachmentId}/info — metadata only (no body) */
    @GetMapping("/{attachmentId}/info")
    public AttachmentDto info(@PathVariable UUID attachmentId) {
        return AttachmentDto.from(fileService.getInfo(attachmentId));
    }

    /** DELETE /files/{attachmentId} */
    @DeleteMapping("/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID attachmentId,
                       @AuthenticationPrincipal String userId) {
        fileService.delete(attachmentId, UUID.fromString(userId));
    }

    // ---- Range helper ----

    private ResponseEntity<InputStreamResource> serveRange(UUID attachmentId, String rangeHeader,
                                                           String mimeType, long totalSize,
                                                           ContentDisposition cd) {
        try {
            String rangeSpec = rangeHeader.substring("bytes=".length());
            String[] parts   = rangeSpec.split("-");
            long start = Long.parseLong(parts[0].trim());
            long end   = parts.length > 1 && !parts[1].isBlank()
                    ? Long.parseLong(parts[1].trim())
                    : totalSize - 1;

            if (start > end || end >= totalSize) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header("Content-Range", "bytes */" + totalSize)
                        .build();
            }

            long length = end - start + 1;
            InputStream stream = fileService.downloadRange(attachmentId, start, length);

            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes %d-%d/%d".formatted(start, end, totalSize))
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                    .contentType(MediaType.parseMediaType(mimeType))
                    .contentLength(length)
                    .body(new InputStreamResource(stream));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ---- DTO ----

    public record AttachmentDto(
            String id,
            String filename,
            long sizeBytes,
            String mimeType,
            String sha256Hex,
            String messageId
    ) {
        static AttachmentDto from(Attachment a) {
            return new AttachmentDto(
                    a.getId().toString(),
                    a.getFilename(),
                    a.getSizeBytes(),
                    a.getMimeType(),
                    a.getSha256Hex(),
                    a.getMessage() != null ? a.getMessage().getId().toString() : null
            );
        }
    }
}
