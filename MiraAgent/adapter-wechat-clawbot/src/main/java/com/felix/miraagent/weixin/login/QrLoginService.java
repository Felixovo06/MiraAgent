package com.felix.miraagent.weixin.login;

import com.felix.miraagent.weixin.client.ILinkClient;
import com.felix.miraagent.weixin.client.RuntimeConfig;
import com.felix.miraagent.weixin.client.dto.QrCodeResponse;
import com.felix.miraagent.weixin.client.dto.QrStatusResponse;
import com.felix.miraagent.weixin.config.WeixinProperties;
import com.felix.miraagent.weixin.poll.WeixinPoller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

@Slf4j
@RequiredArgsConstructor
public class QrLoginService implements ApplicationRunner {

    private static final int MAX_QR_FETCHES = 3;
    private static final long POLL_INTERVAL_MS = 2_000;

    private final WeixinProperties properties;
    private final ILinkClient iLinkClient;
    private final RuntimeConfig runtimeConfig;
    private final WeixinPoller weixinPoller;

    @Override
    public void run(ApplicationArguments args) {
        if (runtimeConfig.hasToken()) {
            log.info("[Weixin] Token configured, starting poller directly");
            weixinPoller.start();
        } else {
            Thread.ofVirtual().name("weixin-qr-login").start(this::doQrLogin);
        }
    }

    private void doQrLogin() {
        String baseUrl = runtimeConfig.getBaseUrl();
        int qrFetches = 0;

        while (qrFetches < MAX_QR_FETCHES) {
            try {
                QrCodeResponse qrResp = iLinkClient.getQrCode(baseUrl);
                if (qrResp.isError()) {
                    log.error("[Weixin] Failed to get QR code: ret={}, msg={}", qrResp.getRet(), qrResp.getErrmsg());
                    return;
                }

                String qrcode = qrResp.getQrcode();
                log.info("[Weixin] ===================================================");
                log.info("[Weixin] Please scan the QR code with WeChat:");
                log.info("[Weixin] {}", qrResp.getQrcodeImgContent());
                log.info("[Weixin] ===================================================");

                // Poll until status changes
                String status = pollQrStatus(baseUrl, qrcode);

                if ("confirmed".equals(status)) {
                    return; // poller already started inside pollQrStatus
                }
                if ("expired".equals(status)) {
                    qrFetches++;
                    log.warn("[Weixin] QR code expired, re-fetching ({}/{})", qrFetches, MAX_QR_FETCHES);
                    continue;
                }
                // Other terminal error
                return;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("[Weixin] QR login error: {}", e.getMessage(), e);
                return;
            }
        }

        log.error("[Weixin] QR login failed after {} attempts", MAX_QR_FETCHES);
    }

    // Returns the terminal status: "confirmed", "expired", or "error"
    private String pollQrStatus(String baseUrl, String qrcode) throws InterruptedException {
        String currentBaseUrl = baseUrl;
        while (true) {
            Thread.sleep(POLL_INTERVAL_MS);

            QrStatusResponse resp;
            try {
                resp = iLinkClient.getQrStatus(currentBaseUrl, qrcode);
            } catch (RuntimeException e) {
                log.warn("[Weixin] QR status polling failed, retrying: {}", e.getMessage());
                continue;
            }
            if (resp.isError()) {
                log.error("[Weixin] getQrStatus error: ret={}", resp.getRet());
                return "error";
            }

            String status = resp.getStatus();
            log.debug("[Weixin] QR status: {}", status);

            switch (status) {
                case "wait" -> { /* continue polling */ }
                case "scaned" -> log.info("[Weixin] QR scanned, waiting for confirmation...");
                case "scaned_but_redirect" -> {
                    if (resp.getBaseUrl() != null && !resp.getBaseUrl().isBlank()) {
                        log.info("[Weixin] Redirecting to {}", resp.getBaseUrl());
                        runtimeConfig.setBaseUrl(resp.getBaseUrl());
                        currentBaseUrl = resp.getBaseUrl();
                    }
                }
                case "confirmed" -> {
                    log.info("[Weixin] QR confirmed! Starting poller...");
                    runtimeConfig.setBotToken(resp.getBotToken());
                    if (resp.getBaseUrl() != null && !resp.getBaseUrl().isBlank()) {
                        runtimeConfig.setBaseUrl(resp.getBaseUrl());
                    }
                    weixinPoller.start();
                    return "confirmed";
                }
                case "expired" -> {
                    return "expired";
                }
                default -> {
                    log.warn("[Weixin] Unknown QR status: {}", status);
                    return "error";
                }
            }
        }
    }
}
