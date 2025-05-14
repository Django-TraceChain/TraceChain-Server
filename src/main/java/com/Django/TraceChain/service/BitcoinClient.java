/*
 * WalletService에게 전달받은 주소를 검색하고, 해당 주소의 트랜잭션들을 저장
 */
package com.Django.TraceChain.service;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.TransactionRepository;
import com.Django.TraceChain.repository.WalletRepository;
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

        // 1) Wallet 조회 또는 생성
        Wallet wallet = walletRepository.findById(address)
            .orElseGet(() -> walletRepository.save(new Wallet(address, 1, 0L)));

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = apiUrl + "/address/" + address + "/txs";

        List<Transaction> transactionList = new ArrayList<>();

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, String.class);
            JsonNode rootArray = new ObjectMapper().readTree(response.getBody());

            for (JsonNode txNode : rootArray) {
                // 2) Transaction 객체 생성 및 Wallet 연결
                String txid = txNode.path("txid").asText();
                long amount = 0;
                for (JsonNode vout : txNode.path("vout")) {
                    amount += (long) vout.path("value").asDouble();
                }
                LocalDateTime txTime = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(
                        txNode.path("status").path("block_time").asLong()),
                    ZoneOffset.UTC
                );

                Transaction tx = new Transaction(txid, amount, txTime);
                tx.setWallet(wallet);

                // 3) 입력(vin) 관계 생성
                for (JsonNode vin : txNode.path("vin")) {
                    String sender = vin.path("prevout")
                                       .path("scriptpubkey_address")
                                       .asText(null);
                    long val = vin.path("prevout").path("value").asLong(0);
                    if (sender != null) {
                        Transfer t = new Transfer(tx, sender, address, val);
                        tx.addTransfer(t);
                    }
                }

                // 4) 출력(vout) 관계 생성
                for (JsonNode vout : txNode.path("vout")) {
                    String receiver = vout.path("scriptpubkey_address")
                                          .asText(null);
                    long val = vout.path("value").asLong(0);
                    if (receiver != null) {
                        Transfer t = new Transfer(tx, address, receiver, val);
                        tx.addTransfer(t);
                    }
                }

                // 5) 저장 (cascade로 Transfer들도 함께 저장)
                transactionRepository.save(tx);
                transactionList.add(tx);
            }
        } catch (Exception e) {
            System.out.println("Bitcoin getTransactions error: " + e.getMessage());
        }

        return transactionList;
    }



    //getTransactions 오버로드 => 트랜잭션 개수 제한을 걸어두는 경우를 구현
    public List<Transaction> getTransactions(String address, int limit) {
        String token = accessToken.getAccessToken();
        if (token == null) return Collections.emptyList();

        Wallet wallet = walletRepository.findById(address)
                .orElseGet(() -> walletRepository.save(new Wallet(address, 1, 0L)));

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = apiUrl + "/address/" + address + "/txs";

        List<Transaction> transactionList = new ArrayList<>();

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            JsonNode rootArray = new ObjectMapper().readTree(response.getBody());

            int count = 0;
            for (JsonNode txNode : rootArray) {
                if (count >= limit) break;

                String txid = txNode.path("txid").asText();

                Optional<Transaction> existing = transactionRepository.findById(txid);
                if (existing.isPresent()) {
                    transactionList.add(existing.get());
                    continue;
                }

                long amount = 0;
                for (JsonNode vout : txNode.path("vout")) {
                    amount += (long) vout.path("value").asDouble();
                }
                LocalDateTime txTime = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(txNode.path("status").path("block_time").asLong()), ZoneOffset.UTC);

                Transaction tx = new Transaction(txid, amount, txTime);
                tx.setWallet(wallet);

                for (JsonNode vin : txNode.path("vin")) {
                    String sender = vin.path("prevout").path("scriptpubkey_address").asText(null);
                    long val = vin.path("prevout").path("value").asLong(0);
                    if (sender != null) {
                        Transfer t = new Transfer(tx, sender, address, val);
                        tx.addTransfer(t);
                    }
                }

                for (JsonNode vout : txNode.path("vout")) {
                    String receiver = vout.path("scriptpubkey_address").asText(null);
                    long val = vout.path("value").asLong(0);
                    if (receiver != null) {
                        Transfer t = new Transfer(tx, address, receiver, val);
                        tx.addTransfer(t);
                    }
                }

                // ✅ 여기에선 setWallet만으로 충분함
                transactionRepository.save(tx);
                transactionList.add(tx);
                count++;
            }

        } catch (Exception e) {
            System.out.println("Bitcoin getTransactions (limited) error: " + e.getMessage());
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
            for (Transfer transfer : tx.getTransfers()) {
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

    // 비트코인에도 트랜잭션의 제한을 걸고 재귀탐색하는 기능 추가. api호출은 /trace-detailed
    public void traceRecursiveDetailed(String address, int depth, int maxDepth,
                                       Map<Integer, List<Wallet>> depthMap,
                                       Set<String> visited) {
        if (depth > maxDepth || visited.contains(address)) return;

        visited.add(address);

        List<Transaction> transactions = getTransactions(address, 10);
        if (transactions.isEmpty()) return;

        Wallet wallet = walletRepository.findById(address)
                .orElseGet(() -> walletRepository.save(new Wallet(address, 1, 0L)));

        wallet.setTransactions(transactions);
        depthMap.computeIfAbsent(depth, d -> new ArrayList<>()).add(wallet);

        Set<String> nextAddresses = new HashSet<>();
        for (Transaction tx : transactions) {
            for (Transfer t : tx.getTransfers()) {
                if (t.getSender() != null && !visited.contains(t.getSender()))
                    nextAddresses.add(t.getSender());
                if (t.getReceiver() != null && !visited.contains(t.getReceiver()))
                    nextAddresses.add(t.getReceiver());
            }
        }

        for (String next : nextAddresses) {
            traceRecursiveDetailed(next, depth + 1, maxDepth, depthMap, visited);
        }
    }

}
