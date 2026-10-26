package com.miaa.cas.documents;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportingDocumentRepository extends JpaRepository<SupportingDocument, UUID> {

    List<SupportingDocument> findByOwnerTypeAndOwnerIdOrderByUploadedAtAsc(String ownerType, UUID ownerId);

    long countByOwnerTypeAndOwnerIdAndRemovedAtIsNull(String ownerType, UUID ownerId);
}
