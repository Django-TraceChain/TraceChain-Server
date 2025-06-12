package com.Django.TraceChain.service;

import com.Django.TraceChain.model.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

//전체 코드 동일, 패키지 및 import 구문은 생략

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

 private RestTemplate createRestTemplateWithTimeout() {
     SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
     factory.setConnectTimeout(5000);
     factory.setReadTimeout(5000);
     return new RestTemplate(factory);
 }

 @Override
 @Transactional
 public Wallet findAddress(String address) {
     Optional<Wallet> optionalWallet = walletRepository.findById(address);
     if (optionalWallet.isPresent()) return optionalWallet.get();

     String token = accessToken.getAccessToken();
     if (token == null || token.isEmpty()) {
         System.out.printf("[WARN] Access token missing while fetching wallet: %s%n", address);
         return new Wallet(address, 1, BigDecimal.ZERO);
     }

     RestTemplate restTemplate = createRestTemplateWithTimeout();
     HttpHeaders headers = new HttpHeaders();
     headers.setBearerAuth(token);
     HttpEntity<Void> entity = new HttpEntity<>(headers);

     String url = apiUrl + "/address/" + address;

     try {
         ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
         JsonNode root = new ObjectMapper().readTree(response.getBody());

         String addr = root.path("address").asText();
         BigDecimal funded = new BigDecimal(root.path("chain_stats").path("funded_txo_sum").asLong());
         BigDecimal spent = new BigDecimal(root.path("chain_stats").path("spent_txo_sum").asLong());
         BigDecimal balance = funded.subtract(spent).divide(BigDecimal.valueOf(1e8));

         Wallet wallet = new Wallet(addr, 1, balance);
         wallet.setNewlyFetched(true);
         return saveWallet(wallet);

     } catch (Exception e) {
         System.out.printf("[WARN] Failed to fetch wallet from API: %s%n", e.getMessage());
         return new Wallet(address, 1, BigDecimal.ZERO);
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

     BigDecimal total = BigDecimal.ZERO;
     for (JsonNode vout : txNode.path("vout")) {
         long valueSatoshi = vout.path("value").asLong(0);
         BigDecimal valueBTC = BigDecimal.valueOf(valueSatoshi)
                 .divide(BigDecimal.valueOf(100_000_000), 8, RoundingMode.DOWN);
         total = total.add(valueBTC);
     }

     Transaction tx = new Transaction(txid, total, txTime);
     int transferCount = 0, transferLimit = 30;

     for (JsonNode vin : txNode.path("vin")) {
         if (transferCount >= transferLimit) break;
         String sender = vin.path("prevout").path("scriptpubkey_address").asText(null);
         if (sender == null || sender.isEmpty()) sender = ownerAddress;
         BigDecimal valueBTC = BigDecimal.valueOf(vin.path("prevout").path("value").asLong(0))
                 .divide(BigDecimal.valueOf(100_000_000), 8, RoundingMode.DOWN);
         tx.addTransfer(new Transfer(tx, sender, ownerAddress, valueBTC));
         transferCount++;
     }

     for (JsonNode vout : txNode.path("vout")) {
         if (transferCount >= transferLimit) break;
         String receiver = vout.path("scriptpubkey_address").asText(null);
         if (receiver == null || receiver.isEmpty()) receiver = ownerAddress;
         BigDecimal valueBTC = BigDecimal.valueOf(vout.path("value").asLong(0))
                 .divide(BigDecimal.valueOf(100_000_000), 8, RoundingMode.DOWN);
         tx.addTransfer(new Transfer(tx, ownerAddress, receiver, valueBTC));
         transferCount++;
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
     // ✅ 1. DB에서 먼저 트랜잭션을 페이징 조회
     List<Transaction> cached = transactionRepository.findByWalletAddress(address, PageRequest.of(0, limit)).getContent();
     if (!cached.isEmpty()) {
         return cached;
     }

     // ✅ 2. 액세스 토큰 체크
     String token = accessToken.getAccessToken();
     if (token == null || token.isEmpty()) {
         System.out.printf("[WARN] No access token while getting transactions for: %s%n", address);
         return Collections.emptyList();
     }

     Wallet wallet = findAddress(address);  // 이미 DB에 존재하면 빠르게 반환

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
             String reqUrl = (lastTxid == null) ? url : url + "/chain/" + lastTxid;
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
                     wallet.getTransactions().add(tx);
                 }

                 result.add(tx);
                 if (result.size() >= limit) break;
             }

             // Blockstream은 한 페이지당 최대 25개 반환
             if (txs.size() < 25) break;
         }

         saveWallet(wallet);
     } catch (Exception e) {
         System.out.printf("[ERROR] Failed to get transactions for %s: %s%n", address, e.getMessage());
     }

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
     if (depth > maxDepth || visited.contains(address)) return;
     visited.add(address);

     System.out.printf("[TRACE] Depth %d - Processing address: %s%n", depth, address);

     Wallet wallet = walletRepository.findById(address)
             .orElseGet(() -> walletRepository.save(new Wallet(address, 1, BigDecimal.ZERO)));

     List<Transaction> transactions = limited ? getTransactions(address, limit) : getTransactions(address);
     if (transactions == null || transactions.isEmpty()) return;

     Map<String, Transaction> txMap = new LinkedHashMap<>();
     for (Transaction tx : transactions) {
         txMap.put(tx.getTxID(), tx);
         tx.getWallets().add(wallet);
         wallet.getTransactions().add(tx);
     }

     transactionRepository.saveAll(new ArrayList<>(txMap.values()));
     walletRepository.save(wallet);

     if (depthMap != null) {
         depthMap.computeIfAbsent(depth, d -> new ArrayList<>()).add(wallet);
     }

     Set<String> nextAddresses = new HashSet<>();
     for (Transaction tx : txMap.values()) {
         for (Transfer t : tx.getTransfers()) {
             if (!visited.contains(t.getSender())) nextAddresses.add(t.getSender());
             if (!visited.contains(t.getReceiver())) nextAddresses.add(t.getReceiver());
         }
     }

     for (String next : nextAddresses) {
         traceTransactionsRecursiveInternal(next, depth + 1, maxDepth, visited, depthMap, limit, limited);
     }
 }

 @Transactional
 public List<Transaction> getTransactionsByTimeRange(String address, long start, long end, int limit) {
     String token = accessToken.getAccessToken();
     if (token == null || token.isEmpty()) {
         System.out.printf("[WARN] No token while fetching time-ranged transactions for: %s%n", address);
         return Collections.emptyList();
     }

     RestTemplate restTemplate = createRestTemplateWithTimeout();
     HttpHeaders headers = new HttpHeaders();
     headers.setBearerAuth(token);
     HttpEntity<Void> entity = new HttpEntity<>(headers);

     List<Transaction> transactions = new ArrayList<>();
     Set<String> seen = new HashSet<>();
     String lastTxid = null;

     try {
         while (transactions.size() < limit) {
             String requestUrl = (lastTxid != null)
                     ? apiUrl + "/address/" + address + "/txs/chain/" + lastTxid
                     : apiUrl + "/address/" + address + "/txs";

             ResponseEntity<String> response = restTemplate.exchange(requestUrl, HttpMethod.GET, entity, String.class);
             JsonNode root = new ObjectMapper().readTree(response.getBody());
             if (!root.isArray() || root.size() == 0) break;

             for (JsonNode txNode : root) {
                 if (transactions.size() >= limit) break;

                 String txid = txNode.path("txid").asText();
                 if (seen.contains(txid)) continue;
                 seen.add(txid);
                 lastTxid = txid;

                 long blockTime = txNode.path("status").path("block_time").asLong(0);
                 if (blockTime == 0 || blockTime < start || blockTime > end) continue;

                 Transaction tx = transactionRepository.findById(txid)
                         .orElseGet(() -> parseTransaction(txNode, address));
                 if (!transactionRepository.existsById(tx.getTxID())) {
                     tx = saveTransaction(tx);
                 }

                 transactions.add(tx);
             }

             if (root.size() < 25) break;
         }
     } catch (Exception e) {
         System.out.printf("[ERROR] Time-range transaction fetch failed: %s%n", e.getMessage());
     }

     return transactions;
 }

 @Transactional
 public void traceTransactionsByTimeRange(String address, int depth, int maxDepth,
                                          long start, long end, int limit, Set<String> visited) {
     if (depth > maxDepth || visited.contains(address)) return;
     visited.add(address);

     System.out.printf("[TRACE] Depth %d - Time range tracing for address: %s%n", depth, address);

     Wallet wallet = walletRepository.findById(address).orElseGet(() -> findAddress(address));
     wallet = saveWallet(wallet);

     List<Transaction> transactions = getTransactionsByTimeRange(address, start, end, limit);
     for (Transaction tx : transactions) {
         tx.getWallets().add(wallet);
         wallet.getTransactions().add(tx);
         saveTransaction(tx);
     }

     wallet = saveWallet(wallet);

     for (Transaction tx : transactions) {
         for (Transfer t : tx.getTransfers()) {
             if (!visited.contains(t.getSender())) {
                 traceTransactionsByTimeRange(t.getSender(), depth + 1, maxDepth, start, end, limit, visited);
             }
         }
     }
 }
}
