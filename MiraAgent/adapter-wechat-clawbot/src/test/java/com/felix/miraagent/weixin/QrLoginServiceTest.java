package com.felix.miraagent.weixin;

import com.felix.miraagent.weixin.client.ILinkClient;
import com.felix.miraagent.weixin.client.RuntimeConfig;
import com.felix.miraagent.weixin.client.dto.QrStatusResponse;
import com.felix.miraagent.weixin.config.WeixinProperties;
import com.felix.miraagent.weixin.login.QrLoginService;
import com.felix.miraagent.weixin.poll.WeixinPoller;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QrLoginServiceTest {

    @Test
    void qrRedirectSwitchesPollingBaseUrl() throws Exception {
        RuntimeConfig runtimeConfig = new RuntimeConfig();
        runtimeConfig.setBaseUrl("https://old.example");
        RecordingILinkClient client = new RecordingILinkClient();
        RecordingPoller poller = new RecordingPoller();
        QrLoginService service = new QrLoginService(new WeixinProperties(), client, runtimeConfig, poller);

        Method method = QrLoginService.class.getDeclaredMethod("pollQrStatus", String.class, String.class);
        method.setAccessible(true);
        Object status = method.invoke(service, "https://old.example", "qr-1");

        assertEquals("confirmed", status);
        assertEquals(List.of("https://old.example", "https://new.example"), client.statusBaseUrls);
        assertEquals("https://new.example", runtimeConfig.getBaseUrl());
        assertEquals("token-1", runtimeConfig.getBotToken());
        assertTrue(poller.started);
    }

    static class RecordingILinkClient extends ILinkClient {
        private final List<String> statusBaseUrls = new ArrayList<>();
        private int callCount;

        @Override
        public QrStatusResponse getQrStatus(String baseUrl, String qrcode) {
            statusBaseUrls.add(baseUrl);
            QrStatusResponse response = new QrStatusResponse();
            if (callCount++ == 0) {
                response.setStatus("scaned_but_redirect");
                response.setBaseUrl("https://new.example");
            } else {
                response.setStatus("confirmed");
                response.setBaseUrl("https://new.example");
                response.setBotToken("token-1");
            }
            return response;
        }
    }

    static class RecordingPoller extends WeixinPoller {
        private boolean started;

        RecordingPoller() {
            super(null, null, null, null, null, null, "default");
        }

        @Override
        public void start() {
            started = true;
        }
    }
}
