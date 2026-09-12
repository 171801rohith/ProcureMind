package com.procuremind.ai_service.service;

import com.procuremind.ai_service.Repository.ContractAnalysisRepository;
import com.procuremind.ai_service.Repository.ContractMetadataRepository;
import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.dto.ChatDtos;
import com.procuremind.ai_service.entity.ContractAnalysis;
import com.procuremind.ai_service.entity.ContractMetadata;
import com.procuremind.ai_service.entity.PageIndexNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.AbstractMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {
    private final PageIndexNodeRepository nodeRepository;
    private final ContractMetadataRepository metadataRepository;
    private final ContractAnalysisRepository analysisRepository;

    /**
     * Hard ceiling on what a single tool call may hand back to the model.
     *
     * <p>Tool results are injected verbatim into the conversation, so an oversized one
     * silently blows the model's context window and the completion comes back empty. That
     * is not hypothetical: a contract whose headings do not match the parser's patterns
     * collapses into one ROOT node holding the whole document, and returning those tens of
     * thousands of characters is exactly how analysis started failing.
     */
    @Value("${app.retrieval.max-tool-response-chars:12000}")
    private int maxToolResponseChars;

    @Transactional(readOnly = true)
    public String getContractSummary(String documentId) {
        UUID id = parseId(documentId, "getContractSummary", "documentId");
        if (id == null) {
            return "That is not a valid contract id. Use the contract ID given in the request.";
        }
        List<PageIndexNode> nodes = nodeRepository.findByDocumentIdOrderByNodeOrderAsc(id);

        if (nodes.isEmpty()) {
            log.warn("[TOOL] getContractSummary: contract {}: returned NO nodes - the document has not been "
                    + "indexed, so the model has nothing to analyse", documentId);
            return "No indexed sections found for this document.";
        }

        String toc = nodes.stream()
                .map(node -> String.format("Node %s: %s - %s", node.getId(), node.getTitle(), node.getSummary()))
                .collect(Collectors.joining("\n"));

        String bounded = bound(toc, "table of contents for contract " + documentId);
        log.info("[TOOL] getContractSummary: contract {}: returned {} characters across {} node(s)",
                documentId, bounded.length(), nodes.size());
        return bounded;
    }

    @Transactional(readOnly = true)
    public String getClauseContent(String nodeId) {
        UUID id = parseId(nodeId, "getClauseContent", "nodeId");
        if (id == null) {
            return "That is not a valid node id. Call getContractSummary first and use one of "
                    + "the Node ids it lists.";
        }
        return nodeRepository.findById(id)
                .map(node -> {
                    String rawText = node.getRawContext();
                    if (rawText == null || rawText.isBlank()) {
                        log.warn("[TOOL] getClauseContent: clause {}: returned EMPTY raw text", nodeId);
                        rawText = "(this section has no extracted text)";
                    }
                    String body = bound(rawText, "clause " + nodeId);
                    String response = String.format("NodeID: %s%nLevel: %s%nRaw Text: %s",
                            node.getId(), node.getLevel(), body);
                    log.info("[TOOL] getClauseContent: clause {}: returned {} characters", nodeId, response.length());
                    return response;
                })
                .orElseGet(() -> {
                    log.warn("[TOOL] getClauseContent: clause {}: NOT FOUND", nodeId);
                    return "Clause content not found";
                });
    }

    /**
     * Parses an identifier supplied by the model, or returns null.
     *
     * <p>Small models routinely pass a blank or malformed id. Letting
     * {@code UUID.fromString} throw turned that into a tool execution error, which the model
     * answered by calling the tool again, and at temperature zero it made the same choice
     * every time. The conversation grew with each retry until it overflowed the context
     * window and the completion came back empty, which is the "empty response from the
     * model" failure. Returning a corrective message instead lets the model recover.
     */
    private UUID parseId(String raw, String tool, String parameter) {
        if (raw == null || raw.isBlank()) {
            log.warn("[TOOL] {}: called with an empty {}", tool, parameter);
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            log.warn("[TOOL] {}: called with a malformed {}", tool, parameter);
            return null;
        }
    }

    /**
     * Truncates a tool response that would not fit in the model context.
     *
     * <p>Truncating is deliberately preferred over failing: a partial clause still lets the
     * model produce a grounded analysis, whereas an over-long one produces nothing at all.
     * The marker tells the model the text was cut so it does not treat the tail as absent.
     */
    private String bound(String text, String what) {
        if (text.length() <= maxToolResponseChars) {
            return text;
        }
        log.warn("[TOOL] {}: {} characters exceeds the {} character budget; truncating to fit the model context",
                what, text.length(), maxToolResponseChars);

        // Cut on a line boundary. A blind substring left the last index entry as a partial
        // "Node <half-a-UUID>: <half-a-title>", and a model copying that id got a rejection
        // telling it to call getContractSummary again, which burned the tool budget.
        String cut = text.substring(0, maxToolResponseChars);
        int lastBreak = cut.lastIndexOf('\n');
        if (lastBreak > 0) {
            cut = cut.substring(0, lastBreak);
        }
        return cut
                + System.lineSeparator()
                + "... [truncated: this section is longer than the retrieval budget]";
    }

    @Transactional(readOnly = true)
    public String getCachedAnalysis(String contractId) {
        log.info("Executing cache retrieval for contract: {}", contractId);
        UUID id = parseId(contractId, "getCachedAnalysis", "contractId");
        if (id == null) {
            return "That is not a valid contract id. Use discoverContracts to find one.";
        }
        return analysisRepository.findByContractId(id)
                .map(a -> {
                    String baseInfo = String.format("Risk Score: %s%nRecommendation: %s%nStatus: %s%n",
                            a.getRiskScore(), a.getRecommendation(), a.getStatus());
                    String detailedRisks = a.getRisks().stream()
                            .map(risk -> String.format("- [Severity: %s] %s", risk.getSeverity(), risk.getDescription()))
                            .collect(Collectors.joining("\n"));

                    return baseInfo + "\nIdentified Risks:\n" + (detailedRisks.isEmpty() ? "None" : detailedRisks);
                })
                .orElse("No cached analysis available for this contract ID.");
    }

    @Transactional(readOnly = true)
    public String discoverContracts(ChatDtos.SearchCriteria criteria) {
        log.info("Executing contract discovery for criteria: {}", criteria);

        List<ContractMetadata> metadataList = metadataRepository.findAll();

        String results = metadataList.stream()
                .filter(m -> criteria.documentType() == null
                        || (m.getContractType() != null
                            && m.getContractType().equalsIgnoreCase(criteria.documentType())))
                .map(m -> {
                    ContractAnalysis a = analysisRepository.findByContractId(m.getContractId()).orElse(null);
                    return new AbstractMap.SimpleEntry<>(m, a);
                })
                .filter(entry -> {
                    if (criteria.minRiskScore() == null) {
                        return true;
                    }
                    ContractAnalysis analysis = entry.getValue();
                    return analysis != null && analysis.getRiskScore() != null
                            && analysis.getRiskScore() >= criteria.minRiskScore();
                })
                .map(entry -> String.format("- Contract ID: %s | Type: %s | Amount: %s | Risk Score: %s",
                        entry.getKey().getContractId(),
                        entry.getKey().getContractType(),
                        entry.getKey().getAmount(),
                        entry.getValue() == null ? "not analysed yet" : entry.getValue().getRiskScore()))
                .collect(Collectors.joining("\n"));

        return results.isEmpty() ? "No contracts found matching the criteria." : "Found the following contracts:\n" + results;
    }
}
