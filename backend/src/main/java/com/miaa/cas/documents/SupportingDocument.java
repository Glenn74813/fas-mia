package com.miaa.cas.documents;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "supporting_documents")
public class SupportingDocument {

    @Id
    private UUID id;
    @Column(name = "owner_type", nullable = false, length = 30)
    private String ownerType;
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;
    @Column(name = "file_name", nullable = false)
    private String fileName;
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 64)
    private String sha256;
    @Column(name = "scan_status", nullable = false, length = 12)
    private String scanStatus;
    @Column(name = "storage_key", nullable = false, length = 200)
    private String storageKey;
    @Column(name = "uploaded_by_id", nullable = false)
    private UUID uploadedById;
    @Column(name = "uploaded_by", nullable = false, length = 64)
    private String uploadedBy;
    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;
    @Column(name = "removed_at")
    private Instant removedAt;
    @Column(name = "removed_by", length = 64)
    private String removedBy;

    protected SupportingDocument() {
    }

    public SupportingDocument(UUID id, String ownerType, UUID ownerId, String fileName, String contentType, long size,
            String sha256, String scanStatus, String storageKey, UUID uploadedById, String uploadedBy, Instant now) {
        this.id = id;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = size;
        this.sha256 = sha256;
        this.scanStatus = scanStatus;
        this.storageKey = storageKey;
        this.uploadedById = uploadedById;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = now;
    }

    public void remove(Instant now, String actor) {
        removedAt = now;
        removedBy = actor;
    }

    public boolean isActive() { return removedAt == null; }
    public UUID getId() { return id; }
    public String getOwnerType() { return ownerType; }
    public UUID getOwnerId() { return ownerId; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getScanStatus() { return scanStatus; }
    public String getStorageKey() { return storageKey; }
    public String getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public Instant getRemovedAt() { return removedAt; }
    public String getRemovedBy() { return removedBy; }
}
