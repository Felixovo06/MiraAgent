package com.felix.miraagent.api.controller;

import com.felix.miraagent.api.dto.MessageDto;
import com.felix.miraagent.api.service.SessionApiService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionApiService sessionApiService;

    public SessionController(SessionApiService sessionApiService) {
        this.sessionApiService = sessionApiService;
    }

    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<List<MessageDto>> getMessages(@PathVariable String sessionId) {
        return ResponseEntity.ok(sessionApiService.getMessages(sessionId));
    }
}
