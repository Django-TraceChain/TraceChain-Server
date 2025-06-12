package com.Django.TraceChain.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class AccessToken {

    @Value("${blockstream.client-id}")
    private String clientId;

    @Value("${blockstream.client-secret}")
    private String clientSecret;

    @Value("${blockstream.token-url}")
    private String tokenUrl;

    // 캐시된 토큰과 만료 시간
    private String cachedToken;
    private Instant tokenExpiryTime;

    // 토큰을 가져오는 메서드
    public String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiryTime)) {
            // 디버깅 출력
//            System.out.println("Using cached token");
            return cachedToken;
        }


        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        Map<String, String> params = new HashMap<>();
        params.put("client_id", clientId);
        params.put("client_secret", clientSecret);
        params.put("grant_type", "client_credentials");
        params.put("scope", "openid");

        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (body.length() > 0) body.append("&");
            body.append(entry.getKey()).append("=").append(entry.getValue());
        }

        HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                tokenUrl,
                HttpMethod.POST,
                entity,
                Map.class
        );

        Map<String, Object> responseBody = response.getBody();

        if (responseBody == null || !responseBody.containsKey("access_token")) {
            throw new RuntimeException("Failed to retrieve access token");
        }

        cachedToken = (String) responseBody.get("access_token");

        // expires_in: 토큰 유효 시간 (초) → 현재 시간 + 유효시간 - 여유분
        Integer expiresIn = (Integer) responseBody.getOrDefault("expires_in", 300); // 기본 5분
        tokenExpiryTime = Instant.now().plusSeconds(expiresIn - 10); // 여유분 10초

        return cachedToken;
    }
}
