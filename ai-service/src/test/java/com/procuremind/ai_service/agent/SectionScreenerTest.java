package com.procuremind.ai_service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.procuremind.ai_service.dto.SectionScreeningDtos.SectionFinding;
import com.procuremind.ai_service.entity.PageIndexNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The screener runs once per batch, so its failure modes are the ones that would otherwise
 * be multiplied across a long document.
 *
 * <p>It reads a line format rather than JSON, because a local 8B model would not produce a
 * JSON list for this pass: against a real seventy-section contract it answered with 7,192
 * characters of prose for a batch of fifty and 3,250 for a batch of twenty. The tests below
 * therefore lean on tolerance: usable lines are extracted even when the model wraps them in
 * commentary, and a reply with nothing usable is reported as unreadable rather than as
 * "no risk found".
 */
class SectionScreenerTest {

    private static final UUID CONTRACT_ID = UUID.fromString("56a6c95f-8c8f-4b1f-a546-f8418e7f1312");

    private ChatClient screeningClient;
    private SectionScreener screener;
    private List<PageIndexNode> batch;

    @BeforeEach
    void setUp() {
        screeningClient = mock(ChatClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        given(builder.clone()).willReturn(builder);
        given(builder.defaultSystem(anyString())).willReturn(builder);
        given(builder.build()).willReturn(screeningClient);

        screener = new SectionScreener(builder);
        ReflectionTestUtils.setField(screener, "screeningClient", screeningClient);
        batch = List.of(section("Indemnification"), section("Notices"));
    }

    private void givenModelReplies(String reply) {
        given(screeningClient.prompt().user(any(Consumer.class)).call().content()).willReturn(reply);
    }

    private String id(int index) {
        return batch.get(index).getId().toString();
    }

    @Test
    void readsOneFindingPerLine() {
        givenModelReplies(id(0) + " | HIGH | Uncapped client indemnity.\n"
                + id(1) + " | LOW | Standard notice period.");

        Optional<List<SectionFinding>> findings = screener.screen(CONTRACT_ID, batch, 1, 1);

        assertThat(findings).isPresent();
        assertThat(findings.get()).hasSize(2);
        assertThat(findings.get().get(0).severity()).isEqualTo("HIGH");
        assertThat(findings.get().get(0).concern()).isEqualTo("Uncapped client indemnity.");
        assertThat(findings.get().get(1).nodeId()).isEqualToIgnoringCase(id(1));
    }

    @Test
    void findingLinesAreExtractedEvenWhenTheModelWrapsThemInCommentary() {
        // The observed failure was a model that screened correctly but would not keep to a
        // format. Surrounding prose must not cost us the lines it did produce.
        givenModelReplies("""
                Sure, here is my assessment of the sections you listed:

                %s | HIGH | Uncapped client indemnity.

                Let me know if you would like more detail on any of these.
                """.formatted(id(0)));

        assertThat(screener.screen(CONTRACT_ID, batch, 1, 1)).get().asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.list(SectionFinding.class)).hasSize(1);
    }

    @Test
    void aLineWithoutASeverityStillCountsAsAFinding() {
        givenModelReplies(id(0) + " - the liability cap looks one sided");

        Optional<List<SectionFinding>> findings = screener.screen(CONTRACT_ID, batch, 1, 1);

        assertThat(findings).isPresent();
        assertThat(findings.get()).singleElement().satisfies(finding ->
                assertThat(finding.severity()).isEqualTo("MEDIUM"));
    }

    @Test
    void anExplicitNoneIsAValidAnswerRatherThanAFailure() {
        // "None of these carry risk" is a real result for a batch of boilerplate, and it
        // must not be confused with a reply we could not read.
        givenModelReplies("NONE");

        assertThat(screener.screen(CONTRACT_ID, batch, 1, 1)).contains(List.of());
    }

    @Test
    void linesNamingSectionsOutsideTheBatchAreDropped() {
        givenModelReplies(UUID.randomUUID() + " | HIGH | Invented section.");

        // Nothing usable is left, so this is unreadable rather than "no risk here": the
        // caller must keep the batch as evidence instead of assuming it is clean.
        assertThat(screener.screen(CONTRACT_ID, batch, 1, 1)).isEmpty();
    }

    @Test
    void proseWithNoUsableLinesIsReportedAsUnreadable() {
        givenModelReplies("These sections look mostly standard to me, though the indemnity is broad.");

        assertThat(screener.screen(CONTRACT_ID, batch, 1, 1)).isEmpty();
    }

    @Test
    void anEmptyReplyIsReportedAsUnreadable() {
        givenModelReplies("   ");

        assertThat(screener.screen(CONTRACT_ID, batch, 1, 1)).isEmpty();
    }

    @Test
    void aModelFailureIsReportedAsUnreadableRatherThanThrown() {
        // One bad batch must not abort a seventy-section document.
        given(screeningClient.prompt().user(any(Consumer.class)).call().content())
                .willThrow(new IllegalStateException("model unavailable"));

        assertThat(screener.screen(CONTRACT_ID, batch, 1, 1)).isEmpty();
    }

    private static PageIndexNode section(String title) {
        return PageIndexNode.builder()
                .id(UUID.nameUUIDFromBytes(title.getBytes()))
                .documentId(CONTRACT_ID)
                .level(3)
                .title(title)
                .summary("Summary of " + title)
                .rawContext("Raw text of " + title)
                .build();
    }
}
