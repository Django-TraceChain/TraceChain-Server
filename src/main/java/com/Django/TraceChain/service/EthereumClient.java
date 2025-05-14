package com.Django.TraceChain.service;

import com.Django.TraceChain.service.ChainClient;
import org.springframework.stereotype.Service;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

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

            for (JsonNode txNode : result) {
                String txHash = txNode.path("hash").asText();
                long value = new BigDecimal(txNode.path("value").asText()).longValue();
                long timestamp = txNode.path("timeStamp").asLong();

                LocalDateTime time = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(timestamp), ZoneOffset.UTC
                );

                Transaction tx = new Transaction(txHash, value, time);
                tx.setWallet(wallet);

                String from = txNode.path("from").asText();
                String to = txNode.path("to").asText();

                Transfer t = new Transfer(tx, from, to, value);
                tx.addTransfer(t);

                transactionRepository.save(tx);
                txList.add(tx);
            }

        } catch (Exception e) {
            System.out.println("Ethereum getTransactions error: " + e.getMessage());
        }
        return txList;
    }

    
    //트랜잭션을 가져올때 개수를 정해서 가져오는 버전
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
                long value = new BigDecimal(txNode.path("value").asText()).longValue();
                long timestamp = txNode.path("timeStamp").asLong();

                LocalDateTime time = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(timestamp), ZoneOffset.UTC
                );

                Transaction tx = new Transaction(txHash, value, time);
                tx.setWallet(wallet);

                String from = txNode.path("from").asText();
                String to = txNode.path("to").asText();

                Transfer t = new Transfer(tx, from, to, value);
                tx.addTransfer(t);

                transactionRepository.save(tx);
                txList.add(tx);
            }

        } catch (Exception e) {
            System.out.println("Ethereum getTransactions error: " + e.getMessage());
        }
        return txList;
    }


    @Override
    public void traceTransactionsRecursive(String address, int depth, int maxDepth, Set<String> visited) {
        int limit = 10;  // 최근 트랜잭션 개수 제한

        if (depth > maxDepth || visited.contains(address)) return;

        visited.add(address);

        // 최근 limit개 트랜잭션만 조회
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

        // 다음 단계 주소들에 대해 재귀 호출
        for (String next : nextAddresses) {
            traceTransactionsRecursive(next, depth + 1, maxDepth, visited);
        }
    }

    //사용 예시
    //http://localhost:8080/trace-detailed?address=0xEbA88149813BEc1cCcccFDb0daCEFaaa5DE94cB1&chain=ethereum&depth=0&maxDepth=4
    public void traceRecursiveDetailed(String address, int depth, int maxDepth,
                                       Map<Integer, List<Wallet>> depthMap,
                                       Set<String> visited) {
        if (depth > maxDepth || visited.contains(address)) return;

        visited.add(address);

        List<Transaction> transactions = getTransactions(address, 10);
        if (transactions.isEmpty()) return;

        Wallet wallet = walletRepository.findById(address)
                .orElseGet(() -> walletRepository.save(new Wallet(address, 2, 0L)));

        wallet.setTransactions(transactions); // 👈 핵심

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
