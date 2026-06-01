package com.felix.miraagent.api.controller;

import com.felix.miraagent.api.dto.ChatApiRequest;
import com.felix.miraagent.api.dto.ChatApiResponse;
import com.felix.miraagent.api.service.ChatApiService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatApiService chatApiService;

    public ChatController(ChatApiService chatApiService) {
        this.chatApiService = chatApiService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatApiResponse> chat(@RequestBody ChatApiRequest req) {
        return ResponseEntity.ok(chatApiService.chat(req));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatApiRequest req) {
        return chatApiService.stream(req);
    }
}
