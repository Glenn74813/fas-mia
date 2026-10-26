package com.miaa.cas.documents;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.security.CurrentActor;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supporting-document upload, listing and download. Uploads are restricted by size, extension and
 * detected file signature (the client-declared content type is not trusted), hashed with SHA-256 and
 * passed to the malware-scanning hook; infected files are refused.
 */
@Service
public class DocumentService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;

    public record DocumentInfo(UUID id, String fileName, String contentType, long sizeBytes, String sha256,
            String scanStatus, String uploadedBy, Instant uploadedAt, Instant removedAt, String removedBy) {
    }

    public record Download(SupportingDocument document, byte[] content) {
    }

    private static final Map<String, String> TYPES = Map.of(
            "pdf", "application/pdf",
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "csv", "text/csv",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final SupportingDocumentRepository documents;
    private final DocumentStorage storage;
    private final MalwareScanner scanner;
    private final org.springframework.beans.factory.ObjectProvider<DocumentOwnerPolicy> policyProvider;
    private volatile Map<String, DocumentOwnerPolicy> policies;
    private final AuditService audit;
    private final Clock clock;

    public DocumentService(SupportingDocumentRepository documents, DocumentStorage storage, MalwareScanner scanner,
            org.springframework.beans.factory.ObjectProvider<DocumentOwnerPolicy> policyProvider, AuditService audit,
            Clock clock) {
        this.documents = documents;
        this.storage = storage;
        this.scanner = scanner;
        // Resolved lazily: owner modules depend on this service, so eager injection would be circular.
        this.policyProvider = policyProvider;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public DocumentInfo upload(String ownerType, UUID ownerId, String originalName, byte[] content) {
        policy(ownerType).assertCanModify(ownerId);
        if (content == null || content.length == 0 || content.length > MAX_BYTES) {
            throw reject("Files must be between 1 byte and 10 MB.");
        }
        String name = sanitize(originalName);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String type = TYPES.get(ext);
        if (type == null) {
            throw reject("Allowed file types: " + String.join(", ", TYPES.keySet().stream().sorted().toList()) + ".");
        }
        if (!signatureMatches(ext, content)) {
            throw reject("The file content does not match its ." + ext + " extension.");
        }
        String scan = scanner.scan(content);
        if ("INFECTED".equals(scan)) {
            audit.record("DOCUMENT_REJECTED_MALWARE", ownerType, ownerId, Map.of("fileName", name));
            throw reject("The file was rejected by the malware scanner.");
        }
        String key = "doc/" + UUID.randomUUID();
        storage.put(key, content);
        SupportingDocument doc = documents.saveAndFlush(new SupportingDocument(UUID.randomUUID(), ownerType, ownerId, name,
                type, content.length, sha256(content), scan, key, CurrentActor.userId(), CurrentActor.username(),
                Instant.now(clock)));
        audit.record("DOCUMENT_UPLOADED", ownerType, ownerId, Map.of("documentId", doc.getId().toString(),
                "fileName", name, "sha256", doc.getSha256(), "scanStatus", scan));
        return info(doc);
    }

    @Transactional(readOnly = true)
    public List<DocumentInfo> list(String ownerType, UUID ownerId) {
        policy(ownerType).assertCanView(ownerId);
        return documents.findByOwnerTypeAndOwnerIdOrderByUploadedAtAsc(ownerType, ownerId).stream().map(DocumentService::info)
                .toList();
    }

    @Transactional
    public Download download(UUID documentId) {
        SupportingDocument doc = documents.findById(documentId).orElseThrow(() -> ApiException.notFound("Document"));
        policy(doc.getOwnerType()).assertCanView(doc.getOwnerId());
        if (!doc.isActive()) {
            throw ApiException.notFound("Document");
        }
        audit.record("DOCUMENT_DOWNLOADED", doc.getOwnerType(), doc.getOwnerId(), Map.of("documentId", documentId.toString()));
        return new Download(doc, storage.get(doc.getStorageKey()));
    }

    /** Detaches a document from an editable owner. Content is retained for the audit trail. */
    @Transactional
    public DocumentInfo remove(UUID documentId) {
        SupportingDocument doc = documents.findById(documentId).orElseThrow(() -> ApiException.notFound("Document"));
        policy(doc.getOwnerType()).assertCanModify(doc.getOwnerId());
        if (!doc.isActive()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The document was already removed.");
        }
        doc.remove(Instant.now(clock), CurrentActor.username());
        documents.flush();
        audit.record("DOCUMENT_REMOVED", doc.getOwnerType(), doc.getOwnerId(), Map.of("documentId", documentId.toString()));
        return info(doc);
    }

    public long activeCount(String ownerType, UUID ownerId) {
        return documents.countByOwnerTypeAndOwnerIdAndRemovedAtIsNull(ownerType, ownerId);
    }

    private DocumentOwnerPolicy policy(String ownerType) {
        if (policies == null) {
            policies = policyProvider.orderedStream()
                    .collect(Collectors.toMap(DocumentOwnerPolicy::ownerType, Function.identity()));
        }
        DocumentOwnerPolicy p = policies.get(ownerType);
        if (p == null) {
            throw ApiException.notFound("Document owner");
        }
        return p;
    }

    static String sanitize(String name) {
        String base = name == null ? "file" : name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (base.isEmpty() || base.startsWith(".")) {
            base = "file" + base;
        }
        return base.length() > 200 ? base.substring(base.length() - 200) : base;
    }

    static boolean signatureMatches(String ext, byte[] c) {
        return switch (ext) {
            case "pdf" -> startsWith(c, 0x25, 0x50, 0x44, 0x46, 0x2D);
            case "png" -> startsWith(c, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "jpg", "jpeg" -> startsWith(c, 0xFF, 0xD8, 0xFF);
            case "xlsx", "docx" -> startsWith(c, 0x50, 0x4B, 0x03, 0x04);
            case "csv" -> isPlainText(c);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] c, int... sig) {
        if (c.length < sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if ((c[i] & 0xFF) != sig[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPlainText(byte[] c) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(c)).toString();
            return s.chars().noneMatch(ch -> ch < 0x09 || (ch > 0x0D && ch < 0x20));
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException reject(String message) {
        return new ApiException(ErrorCode.FILE_REJECTED, message);
    }

    static DocumentInfo info(SupportingDocument d) {
        return new DocumentInfo(d.getId(), d.getFileName(), d.getContentType(), d.getSizeBytes(), d.getSha256(),
                d.getScanStatus(), d.getUploadedBy(), d.getUploadedAt(), d.getRemovedAt(), d.getRemovedBy());
    }
}
