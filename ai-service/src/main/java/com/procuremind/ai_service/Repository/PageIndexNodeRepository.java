package com.procuremind.ai_service.Repository;

import com.procuremind.ai_service.entity.PageIndexNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PageIndexNodeRepository extends JpaRepository<PageIndexNode, UUID> {
    List<PageIndexNode> findByDocumentIdOrderByNodeOrderAsc(UUID documentId);

    List<PageIndexNode> findByDocumentIdAndSummaryIsNull(UUID documentId);

    boolean existsByDocumentId(UUID documentId);
}
