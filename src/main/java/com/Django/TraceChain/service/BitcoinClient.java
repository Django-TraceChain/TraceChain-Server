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
import org.springframework.transaction.annotation.Transactional;
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

    private Transaction createTransaction(JsonNode txNode, String ownerAddress) {
        String txid = txNode.path("txid").asText();

        // 1. DB에서 기존 Transaction 조회
        Optional<Transaction> existingOpt = transactionRepository.findById(txid);
        if (existingOpt.isPresent()) {
            return existingOpt.get();
        }

        // 2. 없으면 새 객체 생성
        long amount = 0;
        for (JsonNode vout : txNode.path("vout")) {
            amount += (long) (vout.path("value").asDouble() * 1e8);
        }

        LocalDateTime txTime = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(txNode.path("status").path("block_time").asLong()), ZoneOffset.UTC);

        Transaction tx = new Transaction(txid, amount, txTime);

        // 3. Transfer 추가 (입출금 관계)
        for (JsonNode vin : txNode.path("vin")) {
            String sender = vin.path("prevout").path("scriptpubkey_address").asText(null);
            long val = vin.path("prevout").path("value").asLong(0);
            if (sender == null || sender.isEmpty()) sender = ownerAddress != null ? ownerAddress : "unknown";
            Transfer t = new Transfer(tx, sender, null, val);
            t.setReceiver(ownerAddress != null ? ownerAddress : "unknown");
            tx.addTransfer(t);
        }

        for (JsonNode vout : txNode.path("vout")) {
            String receiver = vout.path("scriptpubkey_address").asText(null);
            long val = vout.path("value").asLong(0);
            if (receiver == null || receiver.isEmpty()) receiver = ownerAddress != null ? ownerAddress : "unknown";
            Transfer t = new Transfer(tx, null, receiver, val);
            t.setSender(ownerAddress != null ? ownerAddress : "unknown");
            tx.addTransfer(t);
        }

        return tx;
    }

    @Override
    public List<Transaction> getTransactions(String address) {
        return getTransactions(address, Integer.MAX_VALUE);
    }

    @Override
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

        Map<String, Transaction> transactionMap = new LinkedHashMap<>();

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            JsonNode rootArray = new ObjectMapper().readTree(response.getBody());

            int count = 0;
            for (JsonNode txNode : rootArray) {
                if (count >= limit) break;

                String txid = txNode.path("txid").asText();

                if (transactionMap.containsKey(txid)) continue;

                Transaction tx = createTransaction(txNode, address);

                try {
                    if (!transactionRepository.existsById(txid)) {
                        transactionRepository.save(tx);
                    }
                } catch (Exception e) {
                    if (!e.getMessage().contains("A different object with the same identifier value was already associated with the session")) {
                        throw e;
                    }
                    // 예외 무시하고 계속 진행
                }

                tx.getWallets().add(wallet);
                wallet.addTransaction(tx);

                transactionMap.put(txid, tx);
                count++;
            }

            walletRepository.save(wallet);

        } catch (Exception e) {
            System.out.println("Bitcoin getTransactions error: " + e.getMessage());
        }

        return new ArrayList<>(transactionMap.values());
    }

    @Override
    @Transactional
    public void traceAllTransactionsRecursive(String address, int depth, int maxDepth, Set<String> visited) {
        if (depth > maxDepth || visited.contains(address)) return;
        visited.add(address);

        Wallet wallet = walletRepository.findById(address)
                .orElseGet(() -> walletRepository.save(new Wallet(address, 1, 0L)));
        if (wallet == null) return;

        List<Transaction> transactions = getTransactions(address);
        if (transactions == null || transactions.isEmpty()) return;

        Map<String, Transaction> txMap = new LinkedHashMap<>();
        for (Transaction tx : transactions) {
            txMap.put(tx.getTxID(), tx);
        }

        transactionRepository.saveAll(txMap.values());
        walletRepository.save(wallet); // optional if cascade exists

        Set<String> nextAddresses = new HashSet<>();
        for (Transaction tx : txMap.values()) {
            if (tx.getTransfers() == null) continue;
            for (Transfer transfer : tx.getTransfers()) {
                String from = transfer.getSender();
                String to = transfer.getReceiver();
                if (from != null && !visited.contains(from)) nextAddresses.add(from);
                if (to != null && !visited.contains(to)) nextAddresses.add(to);
            }
        }

        for (String next : nextAddresses) {
            traceAllTransactionsRecursive(next, depth + 1, maxDepth, visited);
        }
    }

    @Transactional
    public void traceLimitedTransactionsRecursive(String address, int depth, int maxDepth,
                                                  Map<Integer, List<Wallet>> depthMap, Set<String> visited) {
        if (depth > maxDepth || visited.contains(address)) return;
        visited.add(address);

        Wallet wallet = walletRepository.findById(address)
                .orElseGet(() -> walletRepository.save(new Wallet(address, 1, 0L)));
        if (wallet == null) return;

        List<Transaction> transactions = getTransactions(address, 10);
        if (transactions == null || transactions.isEmpty()) return;

        Map<String, Transaction> txMap = new LinkedHashMap<>();
        for (Transaction tx : transactions) {
            txMap.put(tx.getTxID(), tx);
        }

        transactionRepository.saveAll(txMap.values());
        walletRepository.save(wallet); // optional

        // 깊이별 지갑 정보 저장
        depthMap.computeIfAbsent(depth, d -> new ArrayList<>()).add(wallet);

        Set<String> nextAddresses = new HashSet<>();
        for (Transaction tx : txMap.values()) {
            if (tx.getTransfers() == null) continue;
            for (Transfer transfer : tx.getTransfers()) {
                String from = transfer.getSender();
                String to = transfer.getReceiver();
                if (from != null && !visited.contains(from)) nextAddresses.add(from);
                if (to != null && !visited.contains(to)) nextAddresses.add(to);
            }
        }

        for (String next : nextAddresses) {
            traceLimitedTransactionsRecursive(next, depth + 1, maxDepth, depthMap, visited);
        }
    }
}
