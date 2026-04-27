package org.nvias.trashtalk.files;

import io.minio.*;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final MinioClient minio;

    @Value("${trashtalk.minio.bucket}")
    private String bucket;

    public StorageService(MinioClient minio) {
        this.minio = minio;
    }

    @PostConstruct
    void ensureBucket() {
        try {
            boolean exists = minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket: {}", bucket);
            }
        } catch (Exception e) {
            log.warn("Could not verify/create MinIO bucket '{}': {}", bucket, e.getMessage());
        }
    }

    public void put(String objectKey, InputStream data, long sizeBytes, String contentType) {
        try {
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(data, sizeBytes, -1)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build());
        } catch (Exception e) {
            throw new StorageException("Upload failed: " + e.getMessage(), e);
        }
    }

    public InputStream get(String objectKey) {
        try {
            return minio.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new StorageException("Download failed: " + e.getMessage(), e);
        }
    }

    public InputStream getRange(String objectKey, long offset, long length) {
        try {
            return minio.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .offset(offset)
                    .length(length)
                    .build());
        } catch (Exception e) {
            throw new StorageException("Range download failed: " + e.getMessage(), e);
        }
    }

    public void delete(String objectKey) {
        try {
            minio.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            log.warn("Delete failed for object {}: {}", objectKey, e.getMessage());
        }
    }

    public static class StorageException extends RuntimeException {
        public StorageException(String msg, Throwable cause) { super(msg, cause); }
    }
}
