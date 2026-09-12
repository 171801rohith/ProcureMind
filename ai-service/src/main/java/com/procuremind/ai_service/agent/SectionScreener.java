package com.procuremind.ai_service.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.procuremind.ai_service.dto.SectionScreeningDtos.SectionFinding;
import com.procuremind.ai_service.entity.PageIndexNode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Screens one batch of contract sections for risk.
 *
 * <p>The model is shown a batch of the section index, every entry of it, and asked which of
 * those sections carry risk. It never chooses what to look at: the caller decides the
 * batches, so the model's judgement is applied to the whole document rather than to the
 * handful of sections it happened to ask for.
 *
 * <p><b>Why the answer is lines rather than JSON.</b> This pass first asked for a JSON list
 * of findings and a local 8B model would not produce one. Against a real seventy-section
 * contract it replied with 7,192 characters of prose for a batch of fifty and 3,250 for a
 * batch of twenty: it was doing the screening, it simply would not wrap the result in a JSON
 * envelope. One line per finding is a format small models hold on to, and parsing it here
 * means a stray sentence around the lines costs nothing. Structured JSON is still used for
 * the final analysis, where the object is small and has been reliable.
 *
 * <p>No tools are attached. That is deliberate and it is what makes this pass bounded:
 * Spring AI's tool loop in {@code OpenAiChatModel.internalCall} recurses for as long as the
 * model keeps emitting tool calls, with no iteration limit anywhere in the framework, so a
 * pass that has no tools cannot loop at all.
 */
@Slf4j
@Service
public class SectionScreener {

    private static final String SCREENING_SYSTEM_PROMPT = """
            You are a procurement risk screener. You are shown part of a contract's section
            index: an id, a title and a short summary for each section.

            Consider EVERY section in the list and decide which ones carry procurement,
            compliance, financial, operational or contractual risk.

            Answer with ONE LINE per risky section, in exactly this format:

            <id> | <HIGH or MEDIUM or LOW> | <one short sentence naming the risk>

            Rules:
            - Copy the id exactly as it appears in the list. Never invent or shorten one.
            - One line per section. No numbering, no bullets, no markdown, no JSON.
            - List only the risky sections. Leave out routine boilerplate.
            - If no section in this batch carries risk, answer with the single word NONE.
            - Write nothing else: no preamble, no explanation, no closing remarks.
            """;

    /** A finding line: an id anywhere on the line, then a severity, then the concern. */
    private static final Pattern FINDING = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
                    + "\\s*[|:\\-]?\\s*(HIGH|MEDIUM|LOW)?\\s*[|:\\-]?\\s*(.*)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NOTHING_FOUND = Pattern.compile("(?im)^\\s*NONE\\.?\\s*$");

    private final ChatClient screeningClient;

    public SectionScreener(ChatClient.Builder builder) {
        this.screeningClient = builder.clone().defaultSystem(SCREENING_SYSTEM_PROMPT).build();
    }

    /**
     * @return the findings for this batch, or empty when the model's reply could not be
     *         understood at all. The caller keeps the batch's summaries as evidence in that
     *         case, so an unreadable reply costs detail but never costs coverage.
     */
    public Optional<List<SectionFinding>> screen(UUID contractId, List<PageIndexNode> batch, int number, int total) {
        String sections = renderIndex(batch);

        String reply;
        try {
            reply = screeningClient.prompt()
                    .user(u -> u.text("""
                                    Contract ID: {contractId}

                                    SECTION INDEX (batch {number} of {total}):
                                    {sections}
                                    """)
                            .param("contractId", contractId.toString())
                            .param("number", String.valueOf(number))
                            .param("total", String.valueOf(total))
                            .param("sections", sections))
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("[SCREENING] Contract {}: batch {}/{} call failed ({})", contractId, number, total, e.toString());
            return Optional.empty();
        }

        return parse(contractId, batch, number, total, reply);
    }

    private Optional<List<SectionFinding>> parse(UUID contractId, List<PageIndexNode> batch,
                                                 int number, int total, String reply) {
        if (reply == null || reply.isBlank()) {
            log.warn("[SCREENING] Contract {}: batch {}/{} returned an empty response", contractId, number, total);
            return Optional.empty();
        }

        Set<String> known = batch.stream().map(node -> node.getId().toString().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        List<SectionFinding> findings = new ArrayList<>();
        int unknownIds = 0;

        for (String line : reply.split("\\R")) {
            Matcher matcher = FINDING.matcher(line.trim());
            if (!matcher.find()) {
                continue;
            }
            String nodeId = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!known.contains(nodeId)) {
                // A mangled or invented id would otherwise reach the deep-read stage and
                // fetch nothing, which is how the previous agent asked for the contract id
                // as if it were a clause id.
                unknownIds++;
                continue;
            }
            findings.add(new SectionFinding(nodeId, severityOf(matcher.group(2)), concernOf(matcher.group(3))));
        }

        if (unknownIds > 0) {
            log.warn("[SCREENING] Contract {}: batch {}/{} named {} section(s) that were not in the batch",
                    contractId, number, total, unknownIds);
        }
        if (!findings.isEmpty()) {
            return Optional.of(List.copyOf(findings));
        }
        if (NOTHING_FOUND.matcher(reply).find()) {
            // An explicit "nothing risky here" is a real answer for a batch of boilerplate
            // and must not be confused with a reply we failed to read.
            return Optional.of(List.of());
        }

        log.warn("[SCREENING] Contract {}: batch {}/{} reply had no usable finding lines (length={})",
                contractId, number, total, reply.length());
        return Optional.empty();
    }

    private static String severityOf(String matched) {
        if (matched == null || matched.isBlank()) {
            return "MEDIUM";
        }
        return matched.trim().toUpperCase(Locale.ROOT);
    }

    private static String concernOf(String matched) {
        String concern = matched == null ? "" : matched.trim();
        return concern.isEmpty() ? "Flagged during screening." : concern;
    }

    /** One line per section. Ids first so the model can copy them without reformatting. */
    private static String renderIndex(List<PageIndexNode> batch) {
        StringBuilder sections = new StringBuilder();
        for (PageIndexNode node : batch) {
            sections.append(node.getId()).append(" | ")
                    .append(node.getTitle() == null ? "(untitled)" : node.getTitle())
                    .append(" | ")
                    .append(node.getSummary() == null ? "(no summary)" : node.getSummary())
                    .append(System.lineSeparator());
        }
        return sections.toString();
    }
}
