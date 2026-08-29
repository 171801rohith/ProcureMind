package com.procuremind.ai_service.service;

import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.agent.IndexingAgent;
import com.procuremind.ai_service.dto.NodeSummary;
import com.procuremind.ai_service.entity.PageIndexNode;
import com.procuremind.ai_service.service.kafka.ContractEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingService {
    private final PageIndexNodeRepository nodeRepository;
    private final IndexingAgent indexingAgent;
    private final ContractEventProducer eventProducer;

    @Transactional
    public void indexContractNodes(UUID documentId) {
        log.info("Starting AI indexing for contract: {}", documentId);

        List<PageIndexNode> unindexedNodes = nodeRepository.findByDocumentIdAndSummaryIsNull(documentId);

        if (unindexedNodes.isEmpty()) {
            log.info("All nodes for contract [{}] are already indexed. Publishing page indexed event.", documentId);
            eventProducer.publishPageIndexed(documentId);
            return;
        }

        int total = unindexedNodes.size();
        int count = 0;
        
        for (PageIndexNode node : unindexedNodes) {
            count++;
            log.info("⏳ [{}/{}] Processing node {}...", count, total, node.getId());

            try {
                NodeSummary aiResponse = indexingAgent.summarize(node.getRawContext());

                node.setTitle(aiResponse.title());
                node.setSummary(aiResponse.summary());

                log.info("✅ [{}/{}] Successfully indexed: \"{}\"", count, total, aiResponse.title());
                Thread.sleep(3000);

            } catch (Exception e) {
                log.error("❌ [{}/{}] Failed node {}: {}", count, total, node.getId(), e.getMessage());
            }
        }

        nodeRepository.saveAll(unindexedNodes);

        eventProducer.publishPageIndexed(documentId);
        log.info("Finished AI indexing for contract: {}. Event published.", documentId);
    }
}
