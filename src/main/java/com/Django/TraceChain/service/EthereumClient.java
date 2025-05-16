package com.Django.TraceChain.service;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.TransactionRepository;
import com.Django.TraceChain.repository.WalletRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service("ethereumClient")
public class EthereumClient implements ChainClient {

    @Value("${etherscan.api-key}")
    private String apiKey;

    @Value("${etherscan.api-url}")
    private String apiUrl;

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public EthereumClient(WalletRepository walletRepository, TransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    public boolean supports(String chainType) {
        return "ethereum".equalsIgnoreCase(chainType);
    }

    @Override
    public Wallet findAddress(String address) {
        try {
            String url = apiUrl + "?module=account&action=balance&address=" + address + "&tag=latest&apikey=" + apiKey;

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            JsonNode root = new ObjectMapper().readTree(response.getBody());
            String result = root.path("result").asText();
            long balance = new BigDecimal(result).divide(BigDecimal.TEN.pow(18)).longValue();

            Wallet wallet = new Wallet(address, 2, balance);
            walletRepository.save(wallet);
            return wallet;
        } catch (Exception e) {
            System.out.println("Ethereum findAddress error: " + e.getMessage());
            return null;
        }
    }


    @Override
    public List<Transaction> getTransactions(String address) {
        List<Transaction> txList = new ArrayList<>();
        try {
            String url = apiUrl
                    + "?module=account"
                    + "&action=txlist"
                    + "&address=" + address
                    + "&startblock=0"
                    + "&endblock=99999999"
                    + "&sort=asc"
                    + "&apikey=" + apiKey;

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode result = new ObjectMapper().readTree(response.getBody()).path("result");

            if (!result.isArray()) return txList;

            Wallet wallet = walletRepository.findById(address)
                    .orElseGet(() -> walletRepository.save(new Wallet(address, 2, 0L)));

            Set<String> existingTxIDs = wallet.getTransactions().stream()
                    .map(Transaction::getTxID)
                    .collect(Collectors.toSet());

            for (JsonNode txNode : result) {
                String txHash = txNode.path("hash").asText();
                if (existingTxIDs.contains(txHash)) continue;

                long value = new BigDecimal(txNode.path("value").asText()).longValue();
                long timestamp = txNode.path("timeStamp").asLong();

                LocalDateTime time = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(timestamp), ZoneOffset.UTC
                );

                Transaction tx = new Transaction(txHash, value, time);

                // 🔁 양방향 관계 설정
                wallet.addTransaction(tx);  // Wallet 쪽에 추가
                tx.getWallets().add(wallet);  // Transaction 쪽에도 추가

                // Transfer 설정
                String from = txNode.path("from").asText();
                String to = txNode.path("to").asText();
                Transfer t = new Transfer(tx, from, to, value);
                tx.addTransfer(t);

                txList.add(tx);
            }

            transactionRepository.saveAll(txList);  // 한번에 저장
            walletRepository.save(wallet);

        } catch (Exception e) {
            System.out.println("Ethereum getTransactions error: " + e.getMessage());
        }
        return txList;
    }


    @Override
    public List<Transaction> getTransactions(String address, int limit) {
        List<Transaction> txList = new ArrayList<>();

        try {
            String url = apiUrl
                    + "?module=account"
                    + "&action=txlist"
                    + "&address=" + address
                    + "&startblock=0"
                    + "&endblock=99999999"
                    + "&page=1"
                    + "&offset=" + limit
                    + "&sort=desc"
                    + "&apikey=" + apiKey;

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode result = new ObjectMapper().readTree(response.getBody()).path("result");

            if (!result.isArray()) return txList;

            Wallet wallet = walletRepository.findById(address)
                    .orElseGet(() -> walletRepository.save(new Wallet(address, 2, 0L)));

            for (JsonNode txNode : result) {
                String txHash = txNode.path("hash").asText();

                // 🔍 트랜잭션 중복 확인
                Transaction tx = transactionRepository.findById(txHash).orElse(null);
                if (tx == null) {
                    long value = new BigDecimal(txNode.path("value").asText()).longValue();
                    long timestamp = txNode.path("timeStamp").asLong();
                    LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC);

                    tx = new Transaction(txHash, value, time);
                }

                // 🔁 양방향 관계 설정
                if (!tx.getWallets().contains(wallet)) {
                    tx.getWallets().add(wallet);
                }
                if (!wallet.getTransactions().contains(tx)) {
                    wallet.addTransaction(tx);
                }

                // 💡 중복 Transfer 체크
                final String sender = txNode.path("from").asText();
                final String receiver = txNode.path("to").asText();
                final long amount = tx.getAmount();

                boolean transferExists = tx.getTransfers().stream().anyMatch(t ->
                        sender.equals(t.getSender()) &&
                                receiver.equals(t.getReceiver()) &&
                                t.getAmount() == amount
                );

                if (!transferExists) {
                    Transfer transfer = new Transfer(tx, sender, receiver, amount);
                    tx.addTransfer(transfer);
                }

                transactionRepository.save(tx);
                txList.add(tx);
            }

            walletRepository.save(wallet);

        } catch (Exception e) {
            System.out.println("Ethereum getTransactions error: " + e.getMessage());
        }

        return txList;
    }





    @Override
    public void traceTransactionsRecursive(String address, int depth, int maxDepth, Set<String> visited) {
        int limit = 10;

        if (depth > maxDepth || visited.contains(address)) return;
        visited.add(address);

        List<Transaction> transactions = getTransactions(address, limit);
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

        for (String next : nextAddresses) {
            traceTransactionsRecursive(next, depth + 1, maxDepth, visited);
        }
    }


    @Transactional
    public void traceRecursiveDetailed(String address, int depth, int maxDepth,
                                       Map<Integer, List<Wallet>> depthMap,
                                       Set<String> visited) {
        if (depth > maxDepth || visited.contains(address)) return;
        visited.add(address);

        List<Transaction> transactions = getTransactions(address, 10);
        if (transactions.isEmpty()) return;

        Wallet wallet = walletRepository.findById(address)
                .orElseGet(() -> walletRepository.save(new Wallet(address, 2, 0L)));

        // 💡 중복 방지: 기존 트랜잭션 ID 모음
        Set<String> existingTxIDs = wallet.getTransactions().stream()
                .map(Transaction::getTxID)
                .collect(Collectors.toSet());

        for (Transaction tx : transactions) {
            // 🔁 트랜잭션이 wallet에 없으면 추가
            if (!existingTxIDs.contains(tx.getTxID())) {
                wallet.addTransaction(tx);
            }

            // 🔁 wallet <-> tx 연결
            if (!tx.getWallets().contains(wallet)) {
                tx.getWallets().add(wallet);
            }

            // 💡 Transfer 객체에도 연결 유지
            if (tx.getTransfers() != null) {
                for (Transfer t : tx.getTransfers()) {
                    t.setTransaction(tx);
                }
            }
        }

        // 🔐 저장 (세션 충돌 방지용으로 saveAll)
        transactionRepository.saveAll(transactions);
        walletRepository.save(wallet);
        depthMap.computeIfAbsent(depth, d -> new ArrayList<>()).add(wallet);

        // 🔁 다음 주소들로 재귀
        Set<String> nextAddresses = new HashSet<>();
        for (Transaction tx : transactions) {
            for (Transfer t : tx.getTransfers()) {
                if (t.getSender() != null && !visited.contains(t.getSender())) {
                    nextAddresses.add(t.getSender());
                }
                if (t.getReceiver() != null && !visited.contains(t.getReceiver())) {
                    nextAddresses.add(t.getReceiver());
                }
            }
        }

        for (String next : nextAddresses) {
            traceRecursiveDetailed(next, depth + 1, maxDepth, depthMap, visited);
        }
    }

}
