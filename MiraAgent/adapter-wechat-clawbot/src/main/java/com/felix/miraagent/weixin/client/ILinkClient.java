package com.felix.miraagent.weixin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.weixin.client.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Slf4j
public class ILinkClient {

    private static final String APP_ID = "bot";
    private static final String CLIENT_VERSION = "131072";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RestClient normalClient;
    private final RestClient longPollClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ILinkClient() {
        SimpleClientHttpRequestFactory normalFactory = new SimpleClientHttpRequestFactory();
        normalFactory.setConnectTimeout(Duration.ofSeconds(10));
        normalFactory.setReadTimeout(Duration.ofSeconds(15));

        SimpleClientHttpRequestFactory longPollFactory = new SimpleClientHttpRequestFactory();
        longPollFactory.setConnectTimeout(Duration.ofSeconds(10));
        // Server holds connection up to 35s; give 45s to avoid spurious timeouts
        longPollFactory.setReadTimeout(Duration.ofSeconds(45));

        this.normalClient = RestClient.builder()
                .requestFactory(normalFactory)
                .defaultHeader("iLink-App-Id", APP_ID)
                .defaultHeader("iLink-App-ClientVersion", CLIENT_VERSION)
                .build();

        this.longPollClient = RestClient.builder()
                .requestFactory(longPollFactory)
                .defaultHeader("iLink-App-Id", APP_ID)
                .defaultHeader("iLink-App-ClientVersion", CLIENT_VERSION)
                .build();
    }

    public QrCodeResponse getQrCode(String baseUrl) {
        return getJson(normalClient, baseUrl + "/ilink/bot/get_bot_qrcode?bot_type=3", QrCodeResponse.class);
    }

    public QrStatusResponse getQrStatus(String baseUrl, String qrcode) {
        return getJson(longPollClient, baseUrl + "/ilink/bot/get_qrcode_status?qrcode=" + qrcode, QrStatusResponse.class);
    }

    public GetUpdatesResponse getUpdates(String baseUrl, String botToken, String cursor) {
        GetUpdatesRequest request = new GetUpdatesRequest(
                cursor != null ? cursor : "",
                BaseInfo.defaults()
        );
        String body = writeJson(request);
        try {
            byte[] responseBody = longPollClient.post()
                    .uri(baseUrl + "/ilink/bot/getupdates")
                    .header("Authorization", "Bearer " + botToken)
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("X-WECHAT-UIN", randomWechatUin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .contentLength(bodyBytes(body).length)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            return parseJson(responseBody, GetUpdatesResponse.class);
        } catch (ResourceAccessException e) {
            if (isSocketTimeout(e)) {
                return emptyUpdates(cursor);
            }
            throw e;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 412) {
                GetUpdatesResponse response = new GetUpdatesResponse();
                response.setRet(-14);
                response.setErrmsg("session timeout");
                return response;
            }
            throw e;
        }
    }

    public void sendMessage(String baseUrl, String botToken, SendMessageRequest request) {
        String body = writeJson(request);
        normalClient.post()
                .uri(baseUrl + "/ilink/bot/sendmessage")
                .header("Authorization", "Bearer " + botToken)
                .header("AuthorizationType", "ilink_bot_token")
                .header("X-WECHAT-UIN", randomWechatUin())
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(bodyBytes(body).length)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private <T> T getJson(RestClient client, String uri, Class<T> responseType) {
        byte[] body = client.get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .body(byte[].class);
        return parseJson(body, responseType);
    }

    private <T> T parseJson(byte[] body, Class<T> responseType) {
        try {
            return objectMapper.readValue(body, responseType);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse iLink response as JSON", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize iLink request as JSON", e);
        }
    }

    private byte[] bodyBytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private GetUpdatesResponse emptyUpdates(String cursor) {
        GetUpdatesResponse response = new GetUpdatesResponse();
        response.setRet(0);
        response.setGetUpdatesBuf(cursor != null ? cursor : "");
        return response;
    }

    private boolean isSocketTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String randomWechatUin() {
        long value = Integer.toUnsignedLong(RANDOM.nextInt());
        return Base64.getEncoder().encodeToString(Long.toString(value).getBytes(StandardCharsets.UTF_8));
    }
}
