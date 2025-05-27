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

    private Wallet safeFindOrCreateWallet(String address) {
        return walletRepository.findById(address).orElseGet(() -> {
            try {
                return walletRepository.save(new Wallet(address, 2, 0L));
            } catch (Exception e) {
                return walletRepository.findById(address).orElse(null);
            }
        });
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

            Wallet wallet = safeFindOrCreateWallet(address);
            wallet.setBalance(balance);
            walletRepository.save(wallet);
            return wallet;
        } catch (Exception e) {
            System.out.println("Ethereum findAddress error: " + e.getMessage());
            return null;
        }
    }

    @Override
    @Transactional
    public List<Transaction> getTransactions(String address) {
        Map<String, Transaction> txMap = new LinkedHashMap<>();
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

            if (!result.isArray()) return new ArrayList<>();

            // 지갑 로딩 또는 생성
            Wallet wallet = walletRepository.findById(address)
                    .orElseGet(() -> walletRepository.save(new Wallet(address, 2, 0L)));

            for (JsonNode txNode : result) {
                String txHash = txNode.path("hash").asText();
                if (txMap.containsKey(txHash)) continue;

                long value = new BigDecimal(txNode.path("value").asText()).longValue();
                long timestamp = txNode.path("timeStamp").asLong();
                LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC);

                Transaction tx = new Transaction(txHash, value, time);
                Transfer t = new Transfer(tx, txNode.path("from").asText(), txNode.path("to").asText(), value);
                tx.addTransfer(t);

                // 관계 설정
                tx.getWallets().add(wallet);
                wallet.addTransaction(tx);

                txMap.put(txHash, tx);
            }

            // 저장 (Cascade 설정이 되어 있으면 트랜잭션만 저장해도 됨)
            transactionRepository.saveAll(txMap.values());
            walletRepository.save(wallet); // 이미 저장된 경우 중복 저장 harmless

        } catch (Exception e) {
            System.out.println("Ethereum getTransactions error: " + e.getMessage());
        }

        return new ArrayList<>(txMap.values());
    }

    @Override
    @Transactional
    public List<Transaction> getTransactions(String address, int limit) {
        Map<String, Transaction> txMap = new LinkedHashMap<>();
        try {
            String url = apiUrl + "?module=account&action=txlist&address=" + address + "&startblock=0&endblock=99999999&page=1&offset=" + limit + "&sort=desc&apikey=" + apiKey;
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode result = new ObjectMapper().readTree(response.getBody()).path("result");

            if (!result.isArray()) return new ArrayList<>();

            Wallet wallet = safeFindOrCreateWallet(address);

            for (JsonNode txNode : result) {
                String txHash = txNode.path("hash").asText();
                if (txMap.containsKey(txHash)) continue;

                long value = new BigDecimal(txNode.path("value").asText()).longValue();
                long timestamp = txNode.path("timeStamp").asLong();
                LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC);

                Transaction tx = new Transaction(txHash, value, time);
                Transfer t = new Transfer(tx, txNode.path("from").asText(), txNode.path("to").asText(), value);
                tx.addTransfer(t);
                tx.getWallets().add(wallet);
                wallet.addTransaction(tx);

                txMap.put(txHash, tx);
            }

            transactionRepository.saveAll(txMap.values());
            walletRepository.save(wallet);
        } catch (Exception e) {
            System.out.println("Ethereum getTransactions error: " + e.getMessage());
        }
        return new ArrayList<>(txMap.values());
    }

    @Override
    @Transactional
    public void traceAllTransactionsRecursive(String address, int depth, int maxDepth, Set<String> visited) {
        if (depth > maxDepth || visited.contains(address)) return;
        visited.add(address);

        Wallet wallet = safeFindOrCreateWallet(address);
        if (wallet == null) return;

        //List<Transaction> transactions = getTransactions(address);
        List<Transaction> transactions = getTransactions(address,500);
        if (transactions.isEmpty()) return;

        Set<String> existingTxIDs = wallet.getTransactions().stream()
                .map(Transaction::getTxID).collect(Collectors.toSet());

        for (Transaction tx : transactions) {
            if (!existingTxIDs.contains(tx.getTxID())) {
                wallet.addTransaction(tx);
            }
            if (!tx.getWallets().contains(wallet)) {
                tx.getWallets().add(wallet);
            }
            tx.getTransfers().forEach(t -> t.setTransaction(tx));
        }

        transactionRepository.saveAll(transactions);
        walletRepository.save(wallet);

        Set<String> nextAddresses = new HashSet<>();
        for (Transaction tx : transactions) {
            for (Transfer transfer : tx.getTransfers()) {
                if (transfer.getSender() != null && !visited.contains(transfer.getSender()))
                    nextAddresses.add(transfer.getSender());
                if (transfer.getReceiver() != null && !visited.contains(transfer.getReceiver()))
                    nextAddresses.add(transfer.getReceiver());
            }
        }

        for (String next : nextAddresses) {
            traceAllTransactionsRecursive(next, depth + 1, maxDepth, visited);
        }
    }

    @Override
    @Transactional
    public void traceLimitedTransactionsRecursive(String address, int depth, int maxDepth,
                                                  Map<Integer, List<Wallet>> depthMap,
                                                  Set<String> visited) {
        if (depth > maxDepth || visited.contains(address)) return;
        visited.add(address);

        Wallet wallet = safeFindOrCreateWallet(address);
        if (wallet == null) return;

        List<Transaction> transactions = getTransactions(address, 10);
        if (transactions.isEmpty()) return;

        Set<String> existingTxIDs = wallet.getTransactions().stream()
                .map(Transaction::getTxID).collect(Collectors.toSet());

        for (Transaction tx : transactions) {
            if (!existingTxIDs.contains(tx.getTxID())) {
                wallet.addTransaction(tx);
            }
            if (!tx.getWallets().contains(wallet)) {
                tx.getWallets().add(wallet);
            }
            tx.getTransfers().forEach(t -> t.setTransaction(tx));
        }

        transactionRepository.saveAll(transactions);
        walletRepository.save(wallet);
        depthMap.computeIfAbsent(depth, d -> new ArrayList<>()).add(wallet);

        Set<String> nextAddresses = new HashSet<>();
        for (Transaction tx : transactions) {
            for (Transfer transfer : tx.getTransfers()) {
                if (transfer.getSender() != null && !visited.contains(transfer.getSender()))
                    nextAddresses.add(transfer.getSender());
                if (transfer.getReceiver() != null && !visited.contains(transfer.getReceiver()))
                    nextAddresses.add(transfer.getReceiver());
            }
        }

        for (String next : nextAddresses) {
            traceLimitedTransactionsRecursive(next, depth + 1, maxDepth, depthMap, visited);
        }
    }
}
