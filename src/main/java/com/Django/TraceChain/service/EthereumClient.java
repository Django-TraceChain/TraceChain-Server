package com.Django.TraceChain.service;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.TransactionRepository;
import com.Django.TraceChain.repository.WalletRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
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
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EthereumClient(WalletRepository walletRepository, TransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    public boolean supports(String chainType) {
        return "ethereum".equalsIgnoreCase(chainType);
    }

    private Wallet safeFindOrCreateWallet(String address) {
        try {
            return walletRepository.findById(address)
                    .orElseGet(() -> walletRepository.save(new Wallet(address, 2, BigDecimal.ZERO)));
        } catch (Exception e) {
            System.out.println("safeFindOrCreateWallet error: " + e.getMessage());
            return null;
        }
    }

    @Override
    @Transactional
    public Wallet findAddress(String address) {
        Optional<Wallet> optionalWallet = walletRepository.findById(address);
        if (optionalWallet.isPresent()) {
            System.out.println("존재하는 지갑: " + address);
            return optionalWallet.get();
        }

        try {
            String url = apiUrl + "?module=account&action=balance&address=" + address + "&tag=latest&apikey=" + apiKey;
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            String result = root.path("result").asText();

            BigDecimal balance = new BigDecimal(result).divide(BigDecimal.TEN.pow(18), 18, RoundingMode.DOWN);

            Wallet wallet = safeFindOrCreateWallet(address);
            if (wallet == null) {
                System.out.println("Wallet 생성 실패");
                return null;
            }

            wallet.setBalance(balance);
            wallet.setNewlyFetched(true);
            walletRepository.save(wallet);
            return wallet;

        } catch (Exception e) {
            System.out.println("Ethereum findAddress error: " + e.getMessage());
            return null;
        }
    }

    private BigDecimal convertToEth(String valueStr) {
        try {
            BigDecimal value = new BigDecimal(valueStr);
            return value.divide(BigDecimal.TEN.pow(18), 18, RoundingMode.DOWN);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * JsonNode 에서 트랜잭션 객체와 transfer 생성 및 연관관계 세팅
     */
    private Transaction createTransactionFromJsonNode(JsonNode txNode, Wallet wallet) {
        String txHash = txNode.path("hash").asText();
        BigDecimal value = convertToEth(txNode.path("value").asText());
        long timestamp = txNode.path("timeStamp").asLong();
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC);

        Transaction tx = new Transaction(txHash, value, time);

        Transfer transfer = new Transfer(
                tx,
                txNode.path("from").asText(),
                txNode.path("to").asText(),
                value
        );
        tx.addTransfer(transfer);

        tx.getWallets().add(wallet);
        wallet.addTransaction(tx);

        return tx;
    }

    @Override
    @Transactional
    public List<Transaction> getTransactions(String address) {
        return getTransactions(address, 500); // 0 or any special value means no limit, fetch all
    }

    @Transactional
    public List<Transaction> getTransactions(String address, int limit) {
    	int pageSize = limit > 0 ? limit : 10; // limit이 0 이하일 경우 기본값 10
        List<Transaction> cached = transactionRepository.findByWalletAddress(address, PageRequest.of(0, pageSize)).getContent();
        if (!cached.isEmpty()) {
            return cached;
        }

        // DB에 충분한 데이터가 없으면 API 호출
        Map<String, Transaction> txMap = new LinkedHashMap<>();
        try {
            // API 호출 부분 기존과 동일
            StringBuilder urlBuilder = new StringBuilder(apiUrl)
                    .append("?module=account")
                    .append("&action=txlist")
                    .append("&address=").append(address)
                    .append("&startblock=0")
                    .append("&endblock=99999999")
                    .append("&sort=").append(limit > 0 ? "desc" : "asc")
                    .append("&apikey=").append(apiKey);

            if (limit > 0) {
                urlBuilder.append("&page=1&offset=").append(limit);
            }

            ResponseEntity<String> response = restTemplate.getForEntity(urlBuilder.toString(), String.class);
            JsonNode result = objectMapper.readTree(response.getBody()).path("result");
            if (!result.isArray()) return Collections.emptyList();

            Wallet wallet = safeFindOrCreateWallet(address);

            for (JsonNode txNode : result) {
                String txHash = txNode.path("hash").asText();
                if (txMap.containsKey(txHash)) continue;

                Transaction tx = createTransactionFromJsonNode(txNode, wallet);
                txMap.put(txHash, tx);
            }

            transactionRepository.saveAll(txMap.values());
            walletRepository.save(wallet);

        } catch (Exception e) {
            System.out.println("Ethereum getTransactions error: " + e.getMessage());
            throw new RuntimeException("Ethereum getTransactions failed", e);
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

        List<Transaction> transactions = getTransactions(address, 500);
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

    private long getBlockNumberByTimestamp(long timestamp) {
        try {
            String url = apiUrl
                    + "?module=block"
                    + "&action=getblocknobytime"
                    + "&timestamp=" + timestamp
                    + "&closest=before"
                    + "&apikey=" + apiKey;

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode result = objectMapper.readTree(response.getBody()).path("result");
            return result.asLong();
        } catch (Exception e) {
            System.out.println("getBlockNumberByTimestamp error: " + e.getMessage());
            return 0L;
        }
    }

    @Override
    @Transactional
    public List<Transaction> getTransactionsByTimeRange(String address, long startTimestamp, long endTimestamp, int limit) {
        Map<String, Transaction> txMap = new LinkedHashMap<>();
        try {
            long startBlock = getBlockNumberByTimestamp(startTimestamp);
            long endBlock = getBlockNumberByTimestamp(endTimestamp);

            String url = apiUrl
                    + "?module=account"
                    + "&action=txlist"
                    + "&address=" + address
                    + "&startblock=" + startBlock
                    + "&endblock=" + endBlock
                    + "&page=1"
                    + "&offset=" + limit
                    + "&sort=asc"
                    + "&apikey=" + apiKey;

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode result = objectMapper.readTree(response.getBody()).path("result");

            if (!result.isArray()) return Collections.emptyList();

            Wallet wallet = safeFindOrCreateWallet(address);

            for (JsonNode txNode : result) {
                String txHash = txNode.path("hash").asText();
                if (txMap.containsKey(txHash)) continue;

                BigDecimal value;
                String valueStr = txNode.path("value").asText();
                try {
                    if (valueStr.startsWith("0x")) {
                        value = new BigDecimal(new BigInteger(valueStr.substring(2), 16))
                                .divide(BigDecimal.TEN.pow(18), 18, RoundingMode.DOWN);
                    } else {
                        value = new BigDecimal(valueStr).divide(BigDecimal.TEN.pow(18), 18, RoundingMode.DOWN);
                    }
                } catch (Exception ex) {
                    System.out.println("value 변환 오류: " + valueStr);
                    value = BigDecimal.ZERO;
                }

                long timestamp = txNode.path("timeStamp").asLong();
                if (timestamp < startTimestamp || timestamp > endTimestamp) continue;

                LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC);

                Transaction tx = transactionRepository.findById(txHash).orElse(null);
                if (tx == null) {
                    tx = new Transaction(txHash, value, time);
                    String from = txNode.path("from").asText();
                    String to = txNode.path("to").asText();

                    Transfer t = new Transfer(tx, from, to, value);
                    tx.addTransfer(t);

                    tx.getWallets().add(wallet);
                    wallet.addTransaction(tx);

                    transactionRepository.save(tx);
                }

                txMap.put(txHash, tx);
            }

            walletRepository.save(wallet);

        } catch (Exception e) {
            System.out.println("Ethereum getTransactionsByTimeRange error: " + e.getMessage());
            throw new RuntimeException("Ethereum getTransactionsByTimeRange failed", e);
        }

        return new ArrayList<>(txMap.values());
    }

    @Transactional
    public void traceTransactionsByTimeRange(String address, int depth, int maxDepth,
                                             long start, long end, int limit, Set<String> visited) {
        if (depth > maxDepth || visited.contains(address)) return;
        visited.add(address);

        List<Transaction> txList = getTransactionsByTimeRange(address, start, end, limit);

        List<Transaction> filtered = txList.stream()
                .filter(tx -> {
                    long ts = tx.getTimestamp().toEpochSecond(ZoneOffset.UTC);
                    return ts >= start && ts <= end;
                })
                .toList();

        if (filtered.isEmpty()) return;

        Wallet wallet = safeFindOrCreateWallet(address);
        Set<String> existingTxIDs = wallet.getTransactions().stream()
                .map(Transaction::getTxID).collect(Collectors.toSet());

        for (Transaction tx : filtered) {
            if (!existingTxIDs.contains(tx.getTxID())) {
                wallet.addTransaction(tx);
            }
            if (!tx.getWallets().contains(wallet)) {
                tx.getWallets().add(wallet);
            }
            tx.getTransfers().forEach(t -> t.setTransaction(tx));
        }

        transactionRepository.saveAll(filtered);
        walletRepository.save(wallet);

        Set<String> nextAddresses = new HashSet<>();
        for (Transaction tx : filtered) {
            for (Transfer tr : tx.getTransfers()) {
                if (tr.getSender() != null && !visited.contains(tr.getSender()))
                    nextAddresses.add(tr.getSender());
                if (tr.getReceiver() != null && !visited.contains(tr.getReceiver()))
                    nextAddresses.add(tr.getReceiver());
            }
        }

        for (String next : nextAddresses) {
            traceTransactionsByTimeRange(next, depth + 1, maxDepth, start, end, limit, visited);
        }
    }
}
