package com.procuremind.ai_service.agent;

import com.procuremind.ai_service.dto.NodeSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class IndexingAgent {

    private final ChatClient chatClient;

    public IndexingAgent(ChatClient.Builder builder) {
        this.chatClient = builder.defaultSystem(
                """
                     You are an expert legal document indexing assistant.
                        Given a raw contract section, extract and generate exactly two things:
                        1. 'title': A concise 3-to-5 word title representing the core topic.
                        2. 'summary': A 1-sentence executive summary of the section's contents.
        
                        Do not invent information. Do not include commentary.
                     """
        ).build();
    }

    public NodeSummary summarize(String rawContext) {
        return chatClient.prompt()
                .user(rawContext)
                .call()
                .entity(NodeSummary.class);
        // The .entity() call handles the JSON-to-Record mapping seamlessly (Appends a rule automatically to generate only JSON)
    }
}
