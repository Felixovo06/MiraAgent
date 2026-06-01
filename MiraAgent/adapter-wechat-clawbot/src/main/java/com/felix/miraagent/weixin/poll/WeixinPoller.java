package com.felix.miraagent.weixin.poll;

import com.felix.miraagent.agent.AgentRuntime;
import com.felix.miraagent.agent.ChatInput;
import com.felix.miraagent.agent.RunResult;
import com.felix.miraagent.weixin.client.ILinkClient;
import com.felix.miraagent.weixin.client.RuntimeConfig;
import com.felix.miraagent.weixin.client.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class WeixinPoller implements DisposableBean {

    private static final int MAX_RETRY = 4;
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long RECOVER_SLEEP_MS = 30_000L;

    private final AgentRuntime agentRuntime;
    private final ILinkClient iLinkClient;
    private final RuntimeConfig runtimeConfig;
    private final ContextTokenStore contextTokenStore;
    private final MessageDeduplicator deduplicator;
    private final UserSessionMapper userSessionMapper;
    private final String characterId;

    private volatile Thread pollerThread;

    public WeixinPoller(AgentRuntime agentRuntime, ILinkClient iLinkClient,
                        RuntimeConfig runtimeConfig, ContextTokenStore contextTokenStore,
                        MessageDeduplicator deduplicator, UserSessionMapper userSessionMapper,
                        String characterId) {
        this.agentRuntime = agentRuntime;
        this.iLinkClient = iLinkClient;
        this.runtimeConfig = runtimeConfig;
        this.contextTokenStore = contextTokenStore;
        this.deduplicator = deduplicator;
        this.userSessionMapper = userSessionMapper;
        this.characterId = characterId;
    }

    public void start() {
        pollerThread = Thread.ofVirtual().name("weixin-poller").start(this::pollLoop);
        log.info("[Weixin] Poller started");
    }

    @Override
    public void destroy() {
        stop();
    }

    public void stop() {
        if (pollerThread != null) {
            pollerThread.interrupt();
            log.info("[Weixin] Poller stopped");
        }
    }

    private void pollLoop() {
        AtomicReference<String> cursor = new AtomicReference<>("");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                GetUpdatesResponse response = getUpdatesWithRetry(cursor.get());

                if (response.isSessionExpired()) {
                    log.warn("[Weixin] Session expired (ret=-14). Re-login may be needed.");
                    Thread.sleep(5_000);
                    continue;
                }

                if (response.isError()) {
                    log.warn("[Weixin] getUpdates error: ret={}, msg={}", response.getRet(), response.getErrmsg());
                    Thread.sleep(5_000);
                    continue;
                }

                if (response.getGetUpdatesBuf() != null) {
                    cursor.set(response.getGetUpdatesBuf());
                }

                for (WeixinMessage msg : response.safeGetMsgs()) {
                    processMessage(msg);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[Weixin] Poller unexpected error: {}", e.getMessage(), e);
                try {
                    Thread.sleep(RECOVER_SLEEP_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private GetUpdatesResponse getUpdatesWithRetry(String cursor) throws InterruptedException {
        long delayMs = INITIAL_BACKOFF_MS;
        Exception lastException = null;

        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            try {
                return iLinkClient.getUpdates(
                        runtimeConfig.getBaseUrl(),
                        runtimeConfig.getBotToken(),
                        cursor
                );
            } catch (Exception e) {
                lastException = e;
                log.warn("[Weixin] getUpdates attempt {} failed: {}", attempt + 1, e.getMessage());
                if (attempt < MAX_RETRY - 1) {
                    Thread.sleep(delayMs);
                    delayMs *= 2;
                }
            }
        }
        throw new RuntimeException("All " + MAX_RETRY + " getUpdates attempts failed", lastException);
    }

    private void processMessage(WeixinMessage msg) {
        if (deduplicator.isDuplicate(msg.getMessageId())) {
            return;
        }

        contextTokenStore.put(msg.getFromUserId(), msg.getContextToken());

        if (msg.getItemList() == null || msg.getItemList().isEmpty()) {
            return;
        }
        MessageItem item = msg.getItemList().get(0);
        if (item.getType() != 1 || item.getTextItem() == null) {
            return;
        }

        String text = item.getTextItem().getText();
        String sessionId = userSessionMapper.getOrCreateSession(msg.getFromUserId());
        log.info("[Weixin] Received text from {}: {}", msg.getFromUserId(), text);

        ChatInput input = ChatInput.builder()
                .userId(msg.getFromUserId())
                .sessionId(sessionId)
                .characterId(characterId)
                .content(text)
                .build();

        try {
            RunResult result = agentRuntime.chat(input);
            if (result.isSuccess() && result.getFinalMessage() != null) {
                sendReply(msg.getFromUserId(), msg.getContextToken(), result.getFinalMessage().getContent());
            } else {
                log.warn("[Weixin] Agent returned non-success status: {}", result.getStatus());
            }
        } catch (Exception e) {
            log.error("[Weixin] Agent error for user {}: {}", msg.getFromUserId(), e.getMessage(), e);
        }
    }

    private void sendReply(String toUserId, String contextToken, String text) {
        MessageItem item = new MessageItem(1, new TextItem(text));

        SendMsg sendMsg = new SendMsg();
        sendMsg.setToUserId(toUserId);
        sendMsg.setContextToken(contextToken);
        sendMsg.setClientId(UUID.randomUUID().toString());
        sendMsg.setMessageType(2);
        sendMsg.setMessageState(2);
        sendMsg.setItemList(List.of(item));

        SendMessageRequest request = new SendMessageRequest(sendMsg, BaseInfo.defaults());
        try {
            iLinkClient.sendMessage(runtimeConfig.getBaseUrl(), runtimeConfig.getBotToken(), request);
            log.info("[Weixin] Reply sent to {}", toUserId);
        } catch (Exception e) {
            log.error("[Weixin] Failed to send reply to {}: {}", toUserId, e.getMessage(), e);
        }
    }
}
