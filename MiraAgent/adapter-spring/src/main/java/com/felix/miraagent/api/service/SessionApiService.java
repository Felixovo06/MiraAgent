package com.felix.miraagent.api.service;

import com.felix.miraagent.api.dto.MessageDto;
import com.felix.miraagent.session.SessionStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SessionApiService {

    private final SessionStore sessionStore;

    public SessionApiService(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    public List<MessageDto> getMessages(String sessionId) {
        return sessionStore.loadMessages(sessionId)
                .stream()
                .map(MessageDto::from)
                .toList();
    }
}
