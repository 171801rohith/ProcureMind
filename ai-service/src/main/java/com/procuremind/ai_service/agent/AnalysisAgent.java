package com.procuremind.ai_service.agent;

import com.procuremind.ai_service.agent.tools.ContractAnalysisTools;
import com.procuremind.ai_service.dto.ContractAnalysisResultDto;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AnalysisAgent {
    private final ChatClient chatClient;

    public AnalysisAgent(ChatClient.Builder builder, ContractAnalysisTools contractAnalysisTools) {
        this.chatClient = builder
                .defaultSystem("""
                        You are a Senior Enterprise Procurement Officer.
                            Analyze contract risks and determine a risk score from 1.0 (Lowest) to 10.0 (Highest).
                        
                            Steps:
                            1. Call getContractSummary to view the document layout.
                            2. Call getClauseContent on high-risk nodes (e.g., Liability, MWBE, Waivers).
                            3. Call getVendorHistory.
                            4. Synthesize findings into the requested JSON format.
                        """)
                .defaultTools(contractAnalysisTools)
                .build();
    }

    public ContractAnalysisResultDto execute(UUID contractId) {
        return chatClient.prompt()
                .user("Analyze contract ID: " + contractId)
                .call()
                .entity(ContractAnalysisResultDto.class);
    }
}
