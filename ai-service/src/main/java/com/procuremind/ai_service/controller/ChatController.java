package com.procuremind.ai_service.controller;

import com.procuremind.ai_service.dto.ChatDtos;
import com.procuremind.ai_service.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class ChatController {
    private final ConversationService conversationService;

    @PostMapping("/chat")
    public ResponseEntity<ChatDtos.ChatResponseDto> chat(@RequestBody ChatDtos.ChatRequestDto request) {
        ChatDtos.ChatResponseDto response = conversationService.handleChat(request);
        return ResponseEntity.ok(response);
    }
}
