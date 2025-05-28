package com.Django.TraceChain.service;

import com.Django.TraceChain.model.Transaction;
import java.time.ZoneId;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.TransactionRepository;
import com.Django.TraceChain.repository.WalletRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service("bitcoinClient")
public class BitcoinClient implements ChainClient {

    private final AccessToken accessToken;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    @Value("${blockstream.api-url}")
    private String apiUrl;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    public BitcoinClient(AccessToken accessToken,
                         WalletRepository walletRepository,
                         TransactionRepository transactionRepository) {
        this.accessToken = accessToken;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    public boolean supports(String chainType) {
        return "bitcoin".equalsIgnoreCase(chainType);
    }

    @Override
    @Transactional
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
            // 직접 merge하지 않고 saveWallet() 호출
            wallet = saveWallet(wallet);
            return wallet;
        } catch (Exception e) {
            System.out.println("Bitcoin findAddress error: " + e.getMessage());
            return null;
        }
    }

    private Transaction parseTransaction(JsonNode txNode, String ownerAddress) {
        String txid = txNode.path("txid").asText();
        System.out.println("[parseTransaction] Start parsing txid: " + txid);

        long amount = 0;
        for (JsonNode vout : txNode.path("vout")) {
            amount += (long) (vout.path("value").asDouble() * 1e8);
        }

        LocalDateTime txTime = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(txNode.path("status").path("block_time").asLong()), ZoneOffset.UTC);

        Transaction tx = new Transaction(txid, amount, txTime);

        for (JsonNode vin : txNode.path("vin")) {
            String sender = vin.path("prevout").path("scriptpubkey_address").asText(null);
            long val = vin.path("prevout").path("value").asLong(0);
            if (sender == null || sender.isEmpty()) sender = ownerAddress != null ? ownerAddress : "unknown";
            System.out.printf("[parseTransaction] vin sender: %s, value: %d\n", sender, val);
            Transfer t = new Transfer(tx, sender, ownerAddress, val);
            tx.addTransfer(t);
        }

        for (JsonNode vout : txNode.path("vout")) {
            String receiver = vout.path("scriptpubkey_address").asText(null);
            long val = vout.path("value").asLong(0);
            if (receiver == null || receiver.isEmpty()) receiver = ownerAddress != null ? ownerAddress : "unknown";
            System.out.printf("[parseTransaction] vout receiver: %s, value: %d\n", receiver, val);
            Transfer t = new Transfer(tx, ownerAddress, receiver, val);
            tx.addTransfer(t);
        }

        System.out.println("[parseTransaction] Finished parsing txid: " + txid);
        return tx;
    }

    @Transactional
    @Override
    public List<Transaction> getTransactions(String address) {
        return getTransactions(address, Integer.MAX_VALUE);
    }

    @Override
    @Transactional
    public List<Transaction> getTransactions(String address, int limit) {
        String token = accessToken.getAccessToken();
        if (token == null) {
            System.out.println("[getTransactions] No access token available.");
            return Collections.emptyList();
        }

        Wallet wallet = walletRepository.findById(address)
                .orElseGet(() -> {
                    System.out.println("[getTransactions] Wallet not found, creating new wallet for address: " + address);
                    Wallet newWallet = new Wallet(address, 1, 0L);
                    return saveWallet(newWallet);
                });

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        Map<String, Transaction> transactionMap = new LinkedHashMap<>();
        Map<String, Transaction> txCache = new HashMap<>();
        Set<String> txSeen = new HashSet<>();

        String url = apiUrl + "/address/" + address + "/txs";
        boolean more = true;
        String lastSeenTxid = null;

        try {
            while (more && transactionMap.size() < limit) {
                String requestUrl = (lastSeenTxid != null)
                        ? apiUrl + "/address/" + address + "/txs/chain/" + lastSeenTxid
                        : url;

                ResponseEntity<String> response = restTemplate.exchange(requestUrl, HttpMethod.GET, entity, String.class);
                JsonNode rootArray = new ObjectMapper().readTree(response.getBody());

                if (!rootArray.isArray() || rootArray.size() == 0) {
                    break;
                }

                for (JsonNode txNode : rootArray) {
                    if (transactionMap.size() >= limit) {
                        more = false;
                        break;
                    }

                    String txid = txNode.path("txid").asText();
                    if (txSeen.contains(txid)) continue;
                    txSeen.add(txid);

                    Transaction tx;

                    if (txCache.containsKey(txid)) {
                        tx = txCache.get(txid);
                    } else {
                        Optional<Transaction> optTx = transactionRepository.findById(txid);
                        if (optTx.isPresent()) {
                            tx = optTx.get();
                        } else {
                            tx = parseTransaction(txNode, address);
                            tx = saveTransaction(tx);
                        }
                        txCache.put(txid, tx);
                    }

                    if (!tx.getWallets().contains(wallet)) {
                        tx.getWallets().add(wallet);
                    }
                    if (!wallet.getTransactions().contains(tx)) {
                        wallet.getTransactions().add(tx);
                    }

                    transactionMap.put(txid, tx);
                    lastSeenTxid = txid;
                }

                if (rootArray.size() < 25) {
                    more = false;
                }
            }

            saveWallet(wallet);
        } catch (Exception e) {
            System.err.println("[getTransactions] error: " + e.getMessage());
            throw new RuntimeException("Failed to fetch transactions", e);
        }

        return new ArrayList<>(transactionMap.values());
    }



    @Transactional(propagation = Propagation.REQUIRED)
    public Transaction saveTransaction(Transaction tx) {
        return entityManager.merge(tx);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Wallet saveWallet(Wallet wallet) {
        return entityManager.merge(wallet);
    }

    @Override
    @Transactional
    public void traceAllTransactionsRecursive(String address, int depth, int maxDepth, Set<String> visited) {
        // depthMap은 사용하지 않으므로 null 전달
        traceTransactionsRecursiveInternal(address, depth, maxDepth, visited, null, null, false);
    }

    @Override
    @Transactional
    public void traceLimitedTransactionsRecursive(String address, int depth, int maxDepth,
                                                  Map<Integer, List<Wallet>> depthMap, Set<String> visited) {
        traceTransactionsRecursiveInternal(address, depth, maxDepth, visited, depthMap, 5, true);
    }

    @Transactional
    private void traceTransactionsRecursiveInternal(String address, int depth, int maxDepth, Set<String> visited,
                                                    Map<Integer, List<Wallet>> depthMap,
                                                    Integer limit, boolean limited) {
        if (depth > maxDepth || visited.contains(address)) {
            return;
        }

        visited.add(address);

        List<Transaction> transactions = limited ? getTransactions(address, limit) : getTransactions(address);

        // 동시 수정 방지를 위해 먼저 sender들을 수집
        List<String> toVisit = new ArrayList<>();
        Map<String, Transaction> senderTxMap = new HashMap<>();

        for (Transaction tx : transactions) {
            for (Transfer transfer : tx.getTransfers()) {
                String sender = transfer.getSender();
                if (!visited.contains(sender)) {
                    toVisit.add(sender);
                    senderTxMap.put(sender, tx);  // sender -> 해당 tx 매핑
                }
            }
        }

        // 반복문 밖에서 visited 수정 및 재귀 호출
        for (String sender : toVisit) {
            visited.add(sender);

            // depthMap에 Wallet 추가
            if (depthMap != null) {
                Wallet wallet = new Wallet();
                wallet.setAddress(sender);
                wallet.setType(1);  // 실제 체인 타입으로 설정 필요
                wallet.addTransaction(senderTxMap.get(sender));
                depthMap.computeIfAbsent(depth + 1, k -> new ArrayList<>()).add(wallet);
            }

            traceTransactionsRecursiveInternal(sender, depth + 1, maxDepth, visited, depthMap, limit, limited);
        }
    }
    
    @Transactional
    public List<Transaction> getTransactionsByTimeRange(String address, long start, long end, int limit) {
        String token = accessToken.getAccessToken();
        if (token == null) {
            System.out.println("[getTransactionsByTimeRange] No access token available.");
            return Collections.emptyList();
        }

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        List<Transaction> transactions = new ArrayList<>();
        Set<String> seenTxids = new HashSet<>();

        String lastSeenTxid = null;
        boolean more = true;

        try {
            while (more && transactions.size() < limit) {
                String requestUrl = (lastSeenTxid != null)
                        ? apiUrl + "/address/" + address + "/txs/chain/" + lastSeenTxid
                        : apiUrl + "/address/" + address + "/txs";

                ResponseEntity<String> response = restTemplate.exchange(requestUrl, HttpMethod.GET, entity, String.class);
                JsonNode rootArray = new ObjectMapper().readTree(response.getBody());

                if (!rootArray.isArray() || rootArray.size() == 0) break;

                for (JsonNode txNode : rootArray) {
                    if (transactions.size() >= limit) {
                        more = false;
                        break;
                    }

                    String txid = txNode.path("txid").asText();
                    if (seenTxids.contains(txid)) continue;
                    seenTxids.add(txid);

                    long blockTime = txNode.path("status").path("block_time").asLong(0);
                    if (blockTime == 0) continue;

                    if (blockTime < start) {
                        more = false; // 시간 범위 벗어남, 더 조회하지 않음
                        break;
                    }

                    if (blockTime <= end) {
                        Optional<Transaction> existing = transactionRepository.findById(txid);
                        Transaction tx = existing.orElseGet(() -> parseTransaction(txNode, address));
                        if (existing.isEmpty()) tx = saveTransaction(tx);

                        transactions.add(tx);
                    }

                    lastSeenTxid = txid;
                }

                if (rootArray.size() < 25) more = false;
            }
        } catch (Exception e) {
            System.err.println("[getTransactionsByTimeRange] error: " + e.getMessage());
            throw new RuntimeException("Failed to fetch transactions by time range", e);
        }

        return transactions;
    }

    @Transactional
    public void traceTransactionsByTimeRange(String address, int depth, int maxDepth,
                                             long start, long end, int limit, Set<String> visited) {
        if (depth > maxDepth || visited.contains(address)) {
            return;
        }

        visited.add(address);

        List<Transaction> transactions = getTransactionsByTimeRange(address, start, end, limit);

        List<String> toVisit = new ArrayList<>();

        for (Transaction tx : transactions) {
            for (Transfer transfer : tx.getTransfers()) {
                String sender = transfer.getSender();
                if (!visited.contains(sender)) {
                    toVisit.add(sender);
                }
            }
        }

        for (String sender : toVisit) {
            traceTransactionsByTimeRange(sender, depth + 1, maxDepth, start, end, limit, visited);
        }
    }

}
