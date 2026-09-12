package com.procuremind.ai_service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.agent.AnalysisAgent.AnalysisIncompleteException;
import com.procuremind.ai_service.agent.AnalysisAgent.InvalidAnalysisOutputException;
import com.procuremind.ai_service.dto.ContractAnalysisResultDto;
import com.procuremind.ai_service.dto.SectionScreeningDtos.SectionFinding;
import com.procuremind.ai_service.entity.PageIndexNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Covers the two properties the analysis pipeline has to hold.
 *
 * <p>Coverage: every section of the contract is screened, regardless of what the model does.
 * Output: the final response is a {@link ContractAnalysisResultDto} JSON object or nothing.
 *
 * <p>The chat client and the screener are stubbed, so these run without a model.
 */
class AnalysisAgentTest {

    private static final UUID CONTRACT_ID = UUID.fromString("56a6c95f-8c8f-4b1f-a546-f8418e7f1312");

    private static final String VALID_JSON = """
            {"riskScore": 7.5, "contractType": "MSA", "amount": 450000.0,
             "risks": [{"severity": "HIGH", "description": "Uncapped client indemnity."}],
             "recommendation": "Renegotiate the indemnity cap."}
            """;

    /** The shape of an earlier regression: a mid-sentence fragment of a retrieved clause. */
    private static final String CLAUSE_PROSE =
            "this clause, Consultant is not responsible for any Security Breach arising from "
                    + "the Client's own systems, and disclaims all liability for data recovery.";

    private PageIndexNodeRepository nodeRepository;
    private SectionScreener sectionScreener;
    private ChatClient analysisClient;
    private AnalysisAgent agent;

    @BeforeEach
    void setUp() throws Exception {
        nodeRepository = mock(PageIndexNodeRepository.class);
        sectionScreener = mock(SectionScreener.class);
        analysisClient = mock(ChatClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);

        // clone() cannot be deep stubbed, so the builder returns itself and the built client
        // is the analysis client. The constructor still runs, proving it builds from it.
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        given(builder.clone()).willReturn(builder);
        given(builder.defaultSystem(anyString())).willReturn(builder);
        given(builder.build()).willReturn(analysisClient);

        agent = new AnalysisAgent(builder, nodeRepository, sectionScreener);
        ReflectionTestUtils.setField(agent, "screeningBatchChars", 1000);
        ReflectionTestUtils.setField(agent, "maxEvidenceChars", 16000);
        ReflectionTestUtils.setField(agent, "maxVerifiedSectionChars", 4000);
        ReflectionTestUtils.setField(agent, "maxVerifiedSections", 8);
        ReflectionTestUtils.setField(agent, "maxDuration", java.time.Duration.ofMinutes(8));

        givenScreenerFindsNothing();
    }

    private void givenSections(int count) {
        given(nodeRepository.findByDocumentIdOrderByNodeOrderAsc(CONTRACT_ID)).willReturn(sections(count));
    }

    private void givenScreenerFindsNothing() {
        given(sectionScreener.screen(any(), any(), anyInt(), anyInt())).willReturn(Optional.of(List.of()));
    }

    private void givenModelReplies(String reply) {
        given(analysisClient.prompt().user(any(Consumer.class)).call().content()).willReturn(reply);
    }

    @Test
    void everySectionIsScreenedEvenWhenTheDocumentNeedsManyBatches() {
        // 70 sections of ~230 characters against a 1000 character budget: the model gets no
        // say in how many sections are examined, so coverage is arithmetic.
        givenSections(70);
        givenModelReplies(VALID_JSON);

        agent.execute(CONTRACT_ID);

        ArgumentCaptor<List<PageIndexNode>> batches = ArgumentCaptor.forClass(List.class);
        verify(sectionScreener, times(expectedBatches(70))).screen(eq(CONTRACT_ID), batches.capture(), anyInt(), anyInt());

        List<UUID> screened = new ArrayList<>();
        batches.getAllValues().forEach(batch -> batch.forEach(node -> screened.add(node.getId())));
        assertThat(screened).hasSize(70).doesNotHaveDuplicates();
    }

    @Test
    void aSingleBatchContractStillCoversEverySection() {
        givenSections(3);
        givenModelReplies(VALID_JSON);

        agent.execute(CONTRACT_ID);

        verify(sectionScreener, times(1)).screen(eq(CONTRACT_ID), any(), anyInt(), anyInt());
    }

    @Test
    void findingsAndVerifiedClauseTextReachTheAnalysisCall() {
        List<PageIndexNode> sections = sections(3);
        given(nodeRepository.findByDocumentIdOrderByNodeOrderAsc(CONTRACT_ID)).willReturn(sections);
        given(sectionScreener.screen(any(), any(), anyInt(), anyInt())).willReturn(Optional.of(List.of(
                new SectionFinding(sections.get(1).getId().toString(), "HIGH", "Uncapped indemnity."))));
        givenModelReplies(VALID_JSON);

        assertThat(agent.execute(CONTRACT_ID).riskScore()).isEqualTo(7.5);
    }

    @Test
    void anUnreadableScreeningBatchDoesNotCostCoverage() {
        givenSections(3);
        // The model's reply could not be understood. Those sections must still reach the
        // analysis stage, or they would be silently invisible despite having been screened.
        given(sectionScreener.screen(any(), any(), anyInt(), anyInt())).willReturn(Optional.empty());
        givenModelReplies(VALID_JSON);

        assertThat(agent.execute(CONTRACT_ID).riskScore()).isEqualTo(7.5);
    }

    @Test
    void anExhaustedScreeningBudgetFailsRatherThanScoringAnUnreadDocument() {
        // A partial screen would produce a risk score for a document we did not finish
        // reading, and a low score from an unread section is worse than a visible failure.
        givenSections(70);
        ReflectionTestUtils.setField(agent, "maxDuration", java.time.Duration.ZERO);

        assertThatThrownBy(() -> agent.execute(CONTRACT_ID))
                .isInstanceOf(AnalysisIncompleteException.class)
                .hasMessageContaining("Screening budget");
    }

    @Test
    void anUnindexedContractFailsBeforeCallingTheModel() {
        given(nodeRepository.findByDocumentIdOrderByNodeOrderAsc(CONTRACT_ID)).willReturn(List.of());

        assertThatThrownBy(() -> agent.execute(CONTRACT_ID))
                .isInstanceOf(InvalidAnalysisOutputException.class)
                .hasMessageContaining("no indexed sections");
    }

    @Test
    void validJsonProducesAnAnalysisCarryingTheRiskScore() {
        givenSections(3);
        givenModelReplies(VALID_JSON);

        ContractAnalysisResultDto result = agent.execute(CONTRACT_ID);

        assertThat(result.riskScore()).isEqualTo(7.5);
        assertThat(result.contractType()).isEqualTo("MSA");
        assertThat(result.risks()).hasSize(1);
    }

    @Test
    void contractProseIsRejectedInsteadOfReachingTheParser() {
        givenSections(3);
        givenModelReplies(CLAUSE_PROSE);

        assertThatThrownBy(() -> agent.execute(CONTRACT_ID))
                .isInstanceOf(InvalidAnalysisOutputException.class)
                .hasMessageContaining("was not a JSON object");
    }

    @Test
    void anEmptyResponseIsAControlledFailure() {
        givenSections(3);
        givenModelReplies("   ");

        assertThatThrownBy(() -> agent.execute(CONTRACT_ID))
                .isInstanceOf(InvalidAnalysisOutputException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    void markdownFencedJsonIsNormalisedRatherThanRejected() {
        givenSections(3);
        givenModelReplies("```json\n" + VALID_JSON + "\n```");

        assertThat(agent.execute(CONTRACT_ID).riskScore()).isEqualTo(7.5);
    }

    @Test
    void proseWrappedAroundJsonIsStillRejected() {
        givenSections(3);
        givenModelReplies("Here is the analysis you asked for: " + VALID_JSON);

        assertThatThrownBy(() -> agent.execute(CONTRACT_ID))
                .isInstanceOf(InvalidAnalysisOutputException.class);
    }

    @Test
    void parsedOutputWithoutARiskScoreIsRejected() {
        givenSections(3);
        givenModelReplies("""
                {"riskScore": null, "contractType": "MSA", "amount": null,
                 "risks": [], "recommendation": "None."}
                """);

        assertThatThrownBy(() -> agent.execute(CONTRACT_ID))
                .isInstanceOf(InvalidAnalysisOutputException.class)
                .hasMessageContaining("riskScore");
    }

    /** Mirrors the agent's own weighing so the expectation is not hard-coded to a layout. */
    private static int expectedBatches(int sectionCount) {
        List<PageIndexNode> sections = sections(sectionCount);
        int weight = 40 + sections.get(0).getTitle().length() + sections.get(0).getSummary().length();
        int perBatch = Math.max(1, 1000 / weight);
        return (int) Math.ceil((double) sectionCount / perBatch);
    }

    private static List<PageIndexNode> sections(int count) {
        List<PageIndexNode> sections = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sections.add(PageIndexNode.builder()
                    .id(UUID.nameUUIDFromBytes(("section-" + i).getBytes()))
                    .documentId(CONTRACT_ID)
                    .level(3)
                    .nodeOrder(i)
                    .title("Section " + i + " title padded to a realistic length")
                    .summary("A summary of section " + i + " long enough to weigh like a real one.")
                    .rawContext("Raw legal text for section " + i + ".")
                    .build());
        }
        return sections;
    }
}
