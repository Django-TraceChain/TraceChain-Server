package com.Django.TraceChain.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.Django.TraceChain.Repository.TransactionRepository;
import com.Django.TraceChain.Repository.WalletRepository;
import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AddressService {

    private final AccessToken accessToken;

    @Value("${blockstream.api-url}")
    private String apiUrl;
    
    @Autowired
    private WalletRepository walletRepository;
    
    @Autowired
    private TransactionRepository transactionRepository;
    
    @Autowired
    private TransactionRepository transferRepository;

    @Autowired
    public AddressService(AccessToken accessToken) {
        this.accessToken = accessToken;
    }

    public Wallet findAddress(String address) {
        String token = accessToken.getAccessToken();
        if (token == null) {
            System.out.println("Failed to retrieve access token");
            return null;
        }

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = apiUrl + "/address/" + address;

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                String.class
            );

            String responseBody = response.getBody();

            // JSON 파싱
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseBody);

            String addr = root.path("address").asText();
            long funded = root.path("chain_stats").path("funded_txo_sum").asLong();
            long spent = root.path("chain_stats").path("spent_txo_sum").asLong();
            long balance = funded - spent;

            Wallet wallet = new Wallet(addr, 1, balance);
            walletRepository.save(wallet);  // JPA Repository 이용

            return wallet;
        } catch (Exception e) {
            System.out.println("요청 실패: " + e.getMessage());
            return null;
        }
    }

 // 트랜잭션을 처리하는 메소드
    public List<Transaction> findTxByAddress(String address) {
        String token = accessToken.getAccessToken();
        if (token == null) {
            System.out.println("Failed to retrieve access token");
            return Collections.emptyList();
        }

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String url = apiUrl + "/address/" + address + "/txs";

        List<Transaction> transactionList = new ArrayList<>();

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String responseBody = response.getBody();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootArray = mapper.readTree(responseBody);

            if (rootArray.isArray()) {
                for (JsonNode txNode : rootArray) {
                    String txid = txNode.path("txid").asText();

                    long amount = 0;
                    JsonNode vouts = txNode.path("vout");
                    if (vouts.isArray()) {
                        for (JsonNode vout : vouts) {
                            long value = (long) (vout.path("value").asDouble());
                            amount += value;
                        }
                    }

                    long timestamp = txNode.path("status").path("block_time").asLong();
                    LocalDateTime txTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC);

                    Transaction transaction = new Transaction(txid, amount, txTime);

                    // 입출금 정보 처리 (vin에서 sender, vout에서 receiver)
                    JsonNode inputs = txNode.path("vin");
                    JsonNode outputs = txNode.path("vout");

                    List<Transfer> transfers = new ArrayList<>();

                    // vin 처리: sender
                    if (inputs.isArray()) {
                        for (JsonNode input : inputs) {
                            String sender = input.path("prevout").path("scriptpubkey_address").asText();  // sender는 vin의 scriptpubkey_address
                            long inputAmount = input.path("prevout").path("value").asLong();  // 해당 input의 금액
                            Transfer transfer = new Transfer(transaction, sender, null, inputAmount);  // receiver는 NULL
                            transfers.add(transfer);
                        }
                    }

                    // vout 처리: receiver
                    if (outputs.isArray()) {
                        for (JsonNode output : outputs) {
                            String receiver = output.path("scriptpubkey_address").asText();  // receiver는 vout의 scriptpubkey_address
                            long outputAmount = output.path("value").asLong();  // 해당 output의 금액
                            Transfer transfer = new Transfer(transaction, null, receiver, outputAmount);  // sender는 NULL
                            transfers.add(transfer);
                        }
                    }

                    // 트랜잭션에 입출금 정보 설정
                    transaction.setTransfers(transfers);

                    // Transaction과 그에 속하는 Transfer 정보 DB에 저장
                    transactionRepository.save(transaction);
                    transactionList.add(transaction);
                }
            }
        } catch (Exception e) {
            System.out.println("요청 실패: " + e.getMessage());
        }

        return transactionList;
    }


}
