package com.procuremind.ai_service.service;

import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.agent.IndexingAgent;
import com.procuremind.ai_service.dto.NodeSummary;
import com.procuremind.ai_service.entity.PageIndexNode;
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

        for (PageIndexNode node: unindexedNodes) {
            try {
                NodeSummary aiResponse = indexingAgent.summarize((node.getRawContext()));

                node.setTitle(aiResponse.title());
                node.setSummary(aiResponse.summary());

                log.debug("Indexed Node {}: {}", node.getId(), aiResponse.title());
            } catch (Exception e) {
                log.error("Failed to index node {}. Error: {}", node.getId(), e.getMessage());
            }
        }

        nodeRepository.saveAll(unindexedNodes);

        eventProducer.publishPageIndexed(documentId);
        log.info("Finished AI indexing for contract: {}. Event published.", documentId);
    }
}
