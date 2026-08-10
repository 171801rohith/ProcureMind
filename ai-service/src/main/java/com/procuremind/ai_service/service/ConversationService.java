package com.procuremind.ai_service.service;

import com.procuremind.ai_service.agent.ChatAgent;
import com.procuremind.ai_service.dto.ChatDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {
    private final ChatAgent chatAgent;

    public ChatDtos.ChatResponseDto handleChat(ChatDtos.ChatRequestDto request) {
        log.info("Processing chat for conversation: {}", request.conversationId());

        String aiResponse = chatAgent.execute(request.userMessage(), request.conversationId());

        return new ChatDtos.ChatResponseDto(
                aiResponse,
                Collections.singletonList("Agent executed successfully."),
                Collections.emptyList()
        );
    }
}
