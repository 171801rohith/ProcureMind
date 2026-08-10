package com.procuremind.ai_service.agent;

import com.procuremind.ai_service.agent.tools.ContractAnalysisTools;
import com.procuremind.ai_service.dto.ContractAnalysisResultDto;
import com.procuremind.ai_service.service.RetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class AnalysisAgent {
    private final ChatClient chatClient;
    private final BeanOutputConverter<ContractAnalysisResultDto> outputConverter;
    private final RetrievalService retrievalService;

    public AnalysisAgent(ChatClient.Builder builder, ContractAnalysisTools contractAnalysisTools, RetrievalService retrievalService) throws IOException {
        Resource systemPrompt = new ClassPathResource("prompts/contract-analysis.st");
        String systemPromptText = systemPrompt.getContentAsString(StandardCharsets.UTF_8);

        this.outputConverter = new BeanOutputConverter<>(ContractAnalysisResultDto.class);

        this.chatClient = builder
                .defaultSystem(systemPromptText)
                .defaultTools(contractAnalysisTools)
                .build();
        this.retrievalService = retrievalService;
    }

    public ContractAnalysisResultDto execute(UUID contractId) {
        String format = outputConverter.getFormat();

        String rawResponse = chatClient.prompt()
                .user(u -> u.text("""
                                 Analyze contract ID: {contractId}
                                
                                 Output format: {format}
                                """)
                        .param("contractId", contractId.toString())
                        .param("format", format))
                .call()
                .content();

        log.info("========== RAW MODEL RESPONSE (contract {}) ==========", contractId);
        log.info("{}", rawResponse);
        log.info("=======================================================");

        if (retrievalService == null || Objects.requireNonNull(rawResponse).isBlank()) {
            throw new IllegalStateException(
                    "AnalysisAgent received an empty response from the model for contract " + contractId
                            + ". Check that getContractSummary / getClauseContent / getVendorHistory tool calls "
                            + "are succeeding and actually returning content."
            );
        }

        try {
            return outputConverter.convert(rawResponse);
        } catch (Exception e) {
            log.error("Failed to parse model response into ContractAnalysisResultDto for contract {}. Raw response was: {}",
                    contractId, rawResponse, e);
            throw new IllegalStateException(
                    "Model response for contract " + contractId + " was not valid/complete JSON matching the expected schema.",
                    e
            );
        }
    }
}
