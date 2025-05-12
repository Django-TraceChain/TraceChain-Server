/*
 * WalletService에게 전달받은 주소를 검색하고, 해당 주소의 트랜잭션들을 저장
 */
package com.Django.TraceChain.service;

import com.Django.TraceChain.Repository.TransactionRepository;
import com.Django.TraceChain.Repository.WalletRepository;
import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.WalletRelation;
import com.Django.TraceChain.model.Wallet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service("bitcoinClient")
public class BitcoinClient implements ChainClient {

    private final AccessToken accessToken;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    @Value("${blockstream.api-url}")
    private String apiUrl;

    @Autowired
    public BitcoinClient(AccessToken accessToken, WalletRepository walletRepository, TransactionRepository transactionRepository) {
        this.accessToken = accessToken;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    public boolean supports(String chainType) {
        return "bitcoin".equalsIgnoreCase(chainType);
    }

    @Override
    public Wallet findAddress(String address) {
        String token = accessToken.getAccessToken();
        if (token == null) return null;

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = apiUrl + "/address/" + address;

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            JsonNode root = new ObjectMapper().readTree(response.getBody());

            String addr = root.path("address").asText();
            long funded = root.path("chain_stats").path("funded_txo_sum").asLong();
            long spent = root.path("chain_stats").path("spent_txo_sum").asLong();
            long balance = funded - spent;

            Wallet wallet = new Wallet(addr, 1, balance);
            walletRepository.save(wallet);
            return wallet;
        } catch (Exception e) {
            System.out.println("Bitcoin findAddress error: " + e.getMessage());
            return null;
        }
    }

    @Override
    public List<Transaction> getTransactions(String address) {
        String token = accessToken.getAccessToken();
        if (token == null) return Collections.emptyList();

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = apiUrl + "/address/" + address + "/txs";

        List<Transaction> transactionList = new ArrayList<>();

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            JsonNode rootArray = new ObjectMapper().readTree(response.getBody());

            for (JsonNode txNode : rootArray) {
                String txid = txNode.path("txid").asText();
                long amount = 0;
                for (JsonNode vout : txNode.path("vout")) {
                    amount += (long) vout.path("value").asDouble();
                }

                LocalDateTime txTime = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(txNode.path("status").path("block_time").asLong()),
                        ZoneOffset.UTC
                );

                Transaction transaction = new Transaction(txid, amount, txTime);
                List<WalletRelation> transfers = new ArrayList<>();

                for (JsonNode input : txNode.path("vin")) {
                    String sender = input.path("prevout").path("scriptpubkey_address").asText(null);
                    long val = input.path("prevout").path("value").asLong(0);
                    if (sender != null) {
                        transfers.add(new WalletRelation(transaction, sender, address, val));
                    }
                }

                for (JsonNode output : txNode.path("vout")) {
                    String receiver = output.path("scriptpubkey_address").asText(null);
                    long val = output.path("value").asLong(0);
                    if (receiver != null) {
                        transfers.add(new WalletRelation(transaction, address, receiver, val));
                    }
                }

                transaction.setTransfers(transfers);
                transactionRepository.save(transaction);
                transactionList.add(transaction);
            }
        } catch (Exception e) {
            System.out.println("Bitcoin getTransactions error: " + e.getMessage());
        }

        return transactionList;
    }
    
    public void traceTransactionsRecursive(String address, int depth, int maxDepth, Set<String> visited) {
        if (depth > maxDepth || visited.contains(address)) return;

        visited.add(address);

        // 현재 주소에 대한 트랜잭션 검색 및 저장
        List<Transaction> transactions = getTransactions(address);

        // 입출금 주소 수집
        Set<String> nextAddresses = new HashSet<>();
        for (Transaction tx : transactions) {
            for (WalletRelation transfer : tx.getTransfers()) {
                if (transfer.getSender() != null && !visited.contains(transfer.getSender())) {
                    nextAddresses.add(transfer.getSender());
                }
                if (transfer.getReceiver() != null && !visited.contains(transfer.getReceiver())) {
                    nextAddresses.add(transfer.getReceiver());
                }
            }
        }

        // 다음 단계 주소들에 대해 재귀 호출
        for (String next : nextAddresses) {
            traceTransactionsRecursive(next, depth + 1, maxDepth, visited);
        }
    }

}
