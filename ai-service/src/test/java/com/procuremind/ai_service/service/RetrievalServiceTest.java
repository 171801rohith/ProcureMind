package com.procuremind.ai_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.procuremind.ai_service.Repository.ContractAnalysisRepository;
import com.procuremind.ai_service.Repository.ContractMetadataRepository;
import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.dto.ChatDtos.SearchCriteria;
import com.procuremind.ai_service.entity.AnalysisRisk;
import com.procuremind.ai_service.entity.ContractAnalysis;
import com.procuremind.ai_service.entity.ContractMetadata;
import com.procuremind.ai_service.entity.PageIndexNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * These methods are the retrieval tools the LLM calls, so their output is prompt input.
 * Escaped line breaks and null risk scores used to leak into that text - the first as
 * visible backslash-n noise, the second as a NullPointerException that killed the whole
 * discovery tool for every contract as soon as one was not yet analysed.
 */
@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    private static final UUID CONTRACT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock
    PageIndexNodeRepository nodeRepository;

    @Mock
    ContractMetadataRepository metadataRepository;

    @Mock
    ContractAnalysisRepository analysisRepository;

    @InjectMocks
    RetrievalService retrievalService;

    @Test
    void theCachedAnalysisIsFormattedWithRealLineBreaks() {
        given(analysisRepository.findByContractId(CONTRACT_ID)).willReturn(Optional.of(analysis(7.5)));

        String rendered = retrievalService.getCachedAnalysis(CONTRACT_ID.toString());

        assertThat(rendered).doesNotContain("\\n");
        assertThat(rendered.lines()).contains("Risk Score: 7.5", "Recommendation: Renegotiate the indemnity cap.");
        assertThat(rendered).contains("- [Severity: HIGH] Uncapped liability.");
    }

    @Test
    void aContractWithNoAnalysisIsListedRatherThanCrashingDiscovery() {
        given(metadataRepository.findAll()).willReturn(List.of(metadata("MSA")));
        given(analysisRepository.findByContractId(CONTRACT_ID)).willReturn(Optional.empty());

        String rendered = retrievalService.discoverContracts(new SearchCriteria(null, null, null, null));

        assertThat(rendered).contains("Risk Score: not analysed yet");
    }

    @Test
    void aRiskScoreFilterSkipsContractsThatHaveNoScoreYet() {
        given(metadataRepository.findAll()).willReturn(List.of(metadata("MSA")));
        given(analysisRepository.findByContractId(CONTRACT_ID)).willReturn(Optional.of(analysis(null)));

        String rendered = retrievalService.discoverContracts(new SearchCriteria(null, null, 6.0, null));

        assertThat(rendered).isEqualTo("No contracts found matching the criteria.");
    }

    @Test
    void aContractWithNoRecordedTypeDoesNotBreakATypeFilter() {
        given(metadataRepository.findAll()).willReturn(List.of(metadata(null)));

        String rendered = retrievalService.discoverContracts(new SearchCriteria(null, "MSA", null, null));

        assertThat(rendered).isEqualTo("No contracts found matching the criteria.");
    }

    @Test
    void anOversizedClauseIsTruncatedInsteadOfOverflowingTheModelContext() {
        // The regression this guards: a contract whose headings do not match the parser
        // patterns collapses into one node holding the entire document. Returning all of it
        // blew the context window and the model replied with nothing at all.
        givenToolBudget(500);
        UUID nodeId = UUID.randomUUID();
        given(nodeRepository.findById(nodeId)).willReturn(Optional.of(node(nodeId, "x".repeat(70_000))));

        String rendered = retrievalService.getClauseContent(nodeId.toString());

        assertThat(rendered.length()).isLessThan(1_000);
        assertThat(rendered).contains("truncated");
        assertThat(rendered).contains("NodeID: " + nodeId);
    }

    @Test
    void aClauseWithinBudgetIsReturnedWhole() {
        givenToolBudget(12_000);
        UUID nodeId = UUID.randomUUID();
        given(nodeRepository.findById(nodeId)).willReturn(Optional.of(node(nodeId, "Payment terms are Net 30.")));

        String rendered = retrievalService.getClauseContent(nodeId.toString());

        assertThat(rendered).contains("Payment terms are Net 30.");
        assertThat(rendered).doesNotContain("truncated");
    }

    @Test
    void aClauseWithNoExtractedTextSaysSoRatherThanReturningBlank() {
        givenToolBudget(12_000);
        UUID nodeId = UUID.randomUUID();
        given(nodeRepository.findById(nodeId)).willReturn(Optional.of(node(nodeId, "   ")));

        assertThat(retrievalService.getClauseContent(nodeId.toString()))
                .contains("no extracted text");
    }

    @Test
    void anUnindexedContractReportsThatExplicitlyToTheModel() {
        // Returning an empty string here is what made the model produce nothing, which then
        // surfaced as the misleading "empty response" error.
        givenToolBudget(12_000);
        given(nodeRepository.findByDocumentIdOrderByNodeOrderAsc(CONTRACT_ID)).willReturn(List.of());

        assertThat(retrievalService.getContractSummary(CONTRACT_ID.toString()))
                .isEqualTo("No indexed sections found for this document.");
    }

    @Test
    void theTableOfContentsIsAlsoBounded() {
        givenToolBudget(200);
        given(nodeRepository.findByDocumentIdOrderByNodeOrderAsc(CONTRACT_ID))
                .willReturn(List.of(node(UUID.randomUUID(), "body"), node(UUID.randomUUID(), "body")));

        String toc = retrievalService.getContractSummary(CONTRACT_ID.toString());

        assertThat(toc.length()).isLessThan(400);
        assertThat(toc).contains("truncated");
    }

    @Test
    void aBlankNodeIdIsAnsweredWithGuidanceRatherThanAnException() {
        // The loop that produced the "empty response from the model" failure: the model
        // passed an empty id, UUID.fromString threw, Spring AI fed the error back, and at
        // temperature zero the model made the identical call again, forever.
        assertThatCode(() -> retrievalService.getClauseContent(""))
                .doesNotThrowAnyException();
        assertThat(retrievalService.getClauseContent("")).contains("not a valid node id");
        assertThat(retrievalService.getClauseContent("   ")).contains("getContractSummary");
    }

    @Test
    void aMalformedNodeIdIsAnsweredWithGuidanceRatherThanAnException() {
        assertThatCode(() -> retrievalService.getClauseContent("not-a-uuid"))
                .doesNotThrowAnyException();
        assertThat(retrievalService.getClauseContent("not-a-uuid")).contains("not a valid node id");
    }

    @Test
    void aMalformedContractIdIsAlsoHandledOnTheSummaryTool() {
        assertThatCode(() -> retrievalService.getContractSummary("nope"))
                .doesNotThrowAnyException();
        assertThat(retrievalService.getContractSummary("nope")).contains("not a valid contract id");
    }

    @Test
    void aTruncatedTableOfContentsNeverEndsInAPartialNodeId() {
        // A blind cut left a half-written UUID as the last entry. A model copying it got an
        // "invalid node id" rejection and re-ran getContractSummary, burning the tool budget.
        givenToolBudget(300);
        given(nodeRepository.findByDocumentIdOrderByNodeOrderAsc(CONTRACT_ID))
                .willReturn(List.of(node(UUID.randomUUID(), "body"), node(UUID.randomUUID(), "body"),
                        node(UUID.randomUUID(), "body"), node(UUID.randomUUID(), "body")));

        String toc = retrievalService.getContractSummary(CONTRACT_ID.toString());

        assertThat(toc).contains("truncated");
        String lastEntry = toc.lines()
                .filter(line -> line.startsWith("Node "))
                .reduce((first, second) -> second)
                .orElseThrow();
        // Every surviving entry is a whole line, so every id it offers is complete.
        assertThat(UUID.fromString(lastEntry.substring(5, lastEntry.indexOf(':')))).isNotNull();
    }

    private void givenToolBudget(int chars) {
        ReflectionTestUtils.setField(retrievalService, "maxToolResponseChars", chars);
    }

    private static PageIndexNode node(UUID id, String rawContext) {
        return PageIndexNode.builder()
                .id(id)
                .documentId(CONTRACT_ID)
                .level(2)
                .title("A fairly long section title used to pad the table of contents output")
                .summary("A fairly long section summary used to pad the table of contents output")
                .rawContext(rawContext)
                .build();
    }

    private static ContractAnalysis analysis(Double riskScore) {
        AnalysisRisk risk = new AnalysisRisk();
        risk.setSeverity("HIGH");
        risk.setDescription("Uncapped liability.");

        ContractAnalysis analysis = new ContractAnalysis();
        analysis.setContractId(CONTRACT_ID);
        analysis.setRiskScore(riskScore);
        analysis.setRecommendation("Renegotiate the indemnity cap.");
        analysis.setStatus("COMPLETED");
        analysis.setRisks(List.of(risk));
        return analysis;
    }

    private static ContractMetadata metadata(String contractType) {
        ContractMetadata metadata = new ContractMetadata();
        metadata.setContractId(CONTRACT_ID);
        metadata.setContractType(contractType);
        metadata.setAmount(120000.0);
        return metadata;
    }
}
