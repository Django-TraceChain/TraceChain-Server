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
        // 중복 방지: 이미 존재하면 그대로 반환
        Optional<Wallet> optionalWallet = walletRepository.findById(address);
        if (optionalWallet.isPresent()) return optionalWallet.get();

        String token = accessToken.getAccessToken();
        //if (token == null) return new Wallet(address, 1, 0L); // fallback only

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
            return saveWallet(wallet);
        } catch (Exception e) {
            System.out.println("Bitcoin findAddress error: " + e.getMessage());
            return new Wallet(address, 1, 0L); // fallback only
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Wallet saveWallet(Wallet wallet) {
        return entityManager.merge(wallet);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Transaction saveTransaction(Transaction tx) {
        for (Transfer t : tx.getTransfers()) {
            t.setTransaction(tx);
        }
        return entityManager.merge(tx);
    }

    private Transaction parseTransaction(JsonNode txNode, String ownerAddress) {
        String txid = txNode.path("txid").asText();
        LocalDateTime txTime = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(txNode.path("status").path("block_time").asLong(0)), ZoneOffset.UTC);

        long total = 0;
        for (JsonNode vout : txNode.path("vout")) {
            total += (long) (vout.path("value").asDouble() * 1e8);
        }

        Transaction tx = new Transaction(txid, total, txTime);

        for (JsonNode vin : txNode.path("vin")) {
            String sender = vin.path("prevout").path("scriptpubkey_address").asText(null);
            long value = vin.path("prevout").path("value").asLong(0);
            if (sender == null || sender.isEmpty()) sender = ownerAddress;  // unknown 대신 ownerAddress
            Transfer t = new Transfer(tx, sender, ownerAddress, value);
            tx.addTransfer(t);
        }

        for (JsonNode vout : txNode.path("vout")) {
            String receiver = vout.path("scriptpubkey_address").asText(null);
            long value = vout.path("value").asLong(0);
            if (receiver == null || receiver.isEmpty()) receiver = ownerAddress;  // unknown 대신 ownerAddress
            Transfer t = new Transfer(tx, ownerAddress, receiver, value);
            tx.addTransfer(t);
        }


        return tx;
    }

    @Transactional
    @Override
    public List<Transaction> getTransactions(String address) {
        return getTransactions(address, Integer.MAX_VALUE);
    }

    @Transactional
    @Override
    public List<Transaction> getTransactions(String address, int limit) {
        String token = accessToken.getAccessToken();
        if (token == null) return Collections.emptyList();

        Wallet wallet = findAddress(address);  // 이미 저장되었는지 확인 포함
        List<Transaction> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String url = apiUrl + "/address/" + address + "/txs";
        String lastTxid = null;

        try {
            while (result.size() < limit) {
                String reqUrl = lastTxid == null ? url : url + "/chain/" + lastTxid;
                ResponseEntity<String> response = restTemplate.exchange(reqUrl, HttpMethod.GET, entity, String.class);
                JsonNode txs = new ObjectMapper().readTree(response.getBody());

                if (txs.isEmpty()) break;

                for (JsonNode txNode : txs) {
                    String txid = txNode.path("txid").asText();
                    if (seen.contains(txid)) continue;
                    seen.add(txid);
                    lastTxid = txid;

                    Transaction tx = transactionRepository.findById(txid)
                            .orElseGet(() -> saveTransaction(parseTransaction(txNode, address)));

                    if (!tx.getWallets().contains(wallet)) {
                        tx.getWallets().add(wallet);
                    }
                    if (!wallet.getTransactions().contains(tx)) {
                        wallet.getTransactions().add(tx);
                    }

                    saveTransaction(tx);
                    result.add(tx);

                    if (result.size() >= limit) break;
                }

                if (txs.size() < 25) break;
            }

            saveWallet(wallet);
        } catch (Exception e) {
            throw new RuntimeException("Fetch failed: " + e.getMessage());
        }

        debugPrintWalletRelations(address);
        return result;
    }


    @Override
    @Transactional
    public void traceAllTransactionsRecursive(String address, int depth, int maxDepth, Set<String> visited) {
        if (visited == null) visited = new HashSet<>();
        traceTransactionsRecursiveInternal(address, depth, maxDepth, visited, null, null, false);
    }

    @Override
    @Transactional
    public void traceLimitedTransactionsRecursive(String address, int depth, int maxDepth,
                                                  Map<Integer, List<Wallet>> depthMap, Set<String> visited) {
        if (visited == null) visited = new HashSet<>();
        if (depthMap == null) depthMap = new TreeMap<>();
        traceTransactionsRecursiveInternal(address, depth, maxDepth, visited, depthMap, 5, true);
    }

    @Transactional
    private void traceTransactionsRecursiveInternal(String address, int depth, int maxDepth, Set<String> visited,
                                                    Map<Integer, List<Wallet>> depthMap,
                                                    Integer limit, boolean limited) {
        System.out.printf("[TRACE] Enter depth=%d, address=%s, visited.size=%d%n", depth, address, visited.size());

        if (depth > maxDepth || visited.contains(address)) return;
        visited.add(address);

        Wallet wallet = walletRepository.findById(address)
                .orElseGet(() -> walletRepository.save(new Wallet(address, 1, 0L)));

        List<Transaction> transactions = limited ? getTransactions(address, limit) : getTransactions(address);
        if (transactions == null || transactions.isEmpty()) return;

        Map<String, Transaction> txMap = new LinkedHashMap<>();
        for (Transaction tx : transactions) {
            txMap.put(tx.getTxID(), tx);
            // 지갑-트랜잭션 양방향 연결
            if (!tx.getWallets().contains(wallet)) tx.getWallets().add(wallet);
            if (!wallet.getTransactions().contains(tx)) wallet.getTransactions().add(tx);
        }
        transactionRepository.saveAll(new ArrayList<>(txMap.values()));
        walletRepository.save(wallet);

        if (depthMap != null) {
            depthMap.computeIfAbsent(depth, d -> new ArrayList<>()).add(wallet);
        }

        // 재귀 대상 주소 수집: 모든 트랜스퍼의 sender/receiver 기준
        Set<String> nextAddresses = new HashSet<>();
        for (Transaction tx : txMap.values()) {
            if (tx.getTransfers() == null) continue;
            for (Transfer t : tx.getTransfers()) {
                if (t.getSender() != null && !visited.contains(t.getSender()))
                    nextAddresses.add(t.getSender());
                if (t.getReceiver() != null && !visited.contains(t.getReceiver()))
                    nextAddresses.add(t.getReceiver());
            }
        }

        System.out.printf("[TRACE] 다음 재귀 호출 대상 지갑 수: %d%n", nextAddresses.size());

        for (String next : nextAddresses) {
            traceTransactionsRecursiveInternal(next, depth + 1, maxDepth, visited, depthMap, limit, limited);
        }

        if (depth == 0) {
            System.out.println("========== [DEBUG] 지갑-트랜잭션-트랜스퍼 관계 출력 ==========");
            debugPrintWalletRelations(address);
            System.out.println("========== [DEBUG] 출력 끝 ==========");
        }

        System.out.printf("[TRACE] Exit depth=%d, address=%s%n", depth, address);
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
        if (depth > maxDepth || visited.contains(address)) return;
        visited.add(address);

        Wallet currentWallet = walletRepository.findById(address).orElseGet(() -> findAddress(address));
        currentWallet = saveWallet(currentWallet);

        List<Transaction> transactions = getTransactionsByTimeRange(address, start, end, limit);

        for (Transaction tx : transactions) {
            tx.getWallets().add(currentWallet);
            currentWallet.getTransactions().add(tx);
            saveTransaction(tx);
        }

        currentWallet = saveWallet(currentWallet);

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

        if (depth == 0) {
            debugPrintWalletRelations(address);
        }
    }

    
    private void debugPrintWalletRelations(String rootAddress) {
        System.out.println("\n========== [DEBUG] 지갑-트랜잭션-트랜스퍼 관계 출력 ==========");

        Wallet rootWallet = walletRepository.findById(rootAddress).orElse(null);
        if (rootWallet == null) {
            System.out.println("루트 지갑 정보를 찾을 수 없습니다: " + rootAddress);
            return;
        }

        Set<String> visitedWallets = new HashSet<>();
        Queue<Wallet> queue = new LinkedList<>();
        queue.add(rootWallet);

        while (!queue.isEmpty()) {
            Wallet wallet = queue.poll();
            if (!visitedWallets.add(wallet.getAddress())) continue;

            System.out.println("\n[지갑] 주소: " + wallet.getAddress() + " / 잔액: " + wallet.getBalance() + " sats");

            for (Transaction tx : wallet.getTransactions()) {
                System.out.println("  └─ [트랜잭션] TXID: " + tx.getTxID());
                for (Transfer transfer : tx.getTransfers()) {
                    System.out.printf("      └─ [전송] FROM: %s TO: %s AMOUNT: %d sats\n",
                            transfer.getSender(), transfer.getReceiver(), transfer.getAmount());

                    // 연결된 다른 지갑들도 큐에 추가
                    if (!visitedWallets.contains(transfer.getSender())) {
                        Wallet senderWallet = walletRepository.findById(transfer.getSender()).orElse(null);
                        if (senderWallet != null) queue.add(senderWallet);
                    }
                    if (!visitedWallets.contains(transfer.getReceiver())) {
                        Wallet receiverWallet = walletRepository.findById(transfer.getReceiver()).orElse(null);
                        if (receiverWallet != null) queue.add(receiverWallet);
                    }
                }
            }
        }

        System.out.println("========== [DEBUG] 출력 끝 ==========\n");
    }


}
