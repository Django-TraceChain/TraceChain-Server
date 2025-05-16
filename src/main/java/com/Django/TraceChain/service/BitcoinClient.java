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
import java.util.stream.Collectors;

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

	private Transaction createOrGetTransaction(JsonNode txNode, String ownerAddress) {
	    String txid = txNode.path("txid").asText();

	    Optional<Transaction> existingTxOpt = transactionRepository.findById(txid);
	    if (existingTxOpt.isPresent()) {
	        return existingTxOpt.get();  // 기존 트랜잭션 재사용 (저장하지 말 것)
	    }

	    long amount = 0;
	    for (JsonNode vout : txNode.path("vout")) {
	        amount += (long) vout.path("value").asDouble();
	    }
	    LocalDateTime txTime = LocalDateTime.ofInstant(
	            Instant.ofEpochSecond(txNode.path("status").path("block_time").asLong()), ZoneOffset.UTC);

	    Transaction tx = new Transaction(txid, amount, txTime);
	    tx.getTransfers().clear();

	    for (JsonNode vin : txNode.path("vin")) {
	        String sender = vin.path("prevout").path("scriptpubkey_address").asText(null);
	        long val = vin.path("prevout").path("value").asLong(0);
	        if (sender == null) sender = ownerAddress != null ? ownerAddress : "unknown";
	        Transfer t = new Transfer(tx, sender, null, val);
	        tx.addTransfer(t);
	    }

	    for (JsonNode vout : txNode.path("vout")) {
	        String receiver = vout.path("scriptpubkey_address").asText(null);
	        long val = vout.path("value").asLong(0);
	        if (receiver == null) receiver = ownerAddress != null ? ownerAddress : "unknown";
	        Transfer t = new Transfer(tx, null, receiver, val);
	        tx.addTransfer(t);
	    }

	    for (Transfer t : tx.getTransfers()) {
	        if (t.getSender() == null && t.getReceiver() != null) {
	            t.setSender(ownerAddress != null ? ownerAddress : "unknown");
	        }
	        if (t.getReceiver() == null && t.getSender() != null) {
	            t.setReceiver(ownerAddress != null ? ownerAddress : "unknown");
	        }
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

		List<Transaction> transactionList = new ArrayList<>();

		try {
			ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
			JsonNode rootArray = new ObjectMapper().readTree(response.getBody());

			int count = 0;
			for (JsonNode txNode : rootArray) {
				if (count >= limit) break;

				Transaction tx = createOrGetTransaction(txNode, address);

				// 관계 설정만 하고 저장은 나중에
				if (!wallet.getTransactions().contains(tx)) {
					wallet.addTransaction(tx);
				}
				if (!tx.getWallets().contains(wallet)) {
					tx.getWallets().add(wallet);
				}
				
				transactionList.add(tx);
				count++;
			}
			
			// 🟡 모든 트랜잭션을 저장하기 전에 출력
			System.out.println("\n[DEBUG] Transactions to be saved:");
			for (Transaction t : transactionList) {
				System.out.println("TxID: " + t.getTxID());
				System.out.println("  Associated wallets:");
				for (Wallet w : t.getWallets()) {
					System.out.println("    - " + w.getAddress());
				}
			}

			// 🟢 출력이 끝난 뒤에 저장
			transactionRepository.saveAll(transactionList);
			walletRepository.save(wallet);

		} catch (Exception e) {
			System.out.println("Bitcoin getTransactions error: " + e.getMessage());
		}

		return transactionList;
	}



	@Transactional
	public void traceTransactionsRecursive(String address, int depth, int maxDepth, Set<String> visited) {
	    if (depth > maxDepth || visited.contains(address)) return;
	    visited.add(address);
	    System.out.println("[trace] Depth: " + depth + ", Address: " + address);

	    Wallet wallet = walletRepository.findById(address).orElseGet(() -> findAddress(address));

	    List<Transaction> transactions = getTransactions(address); // getTransactions 내부에서 save 금지 전제

	    if (transactions == null || transactions.isEmpty()) {
	        System.out.println("[trace] No transactions found for address: " + address);
	        return;
	    }

	    System.out.println("[trace] Fetched transactions: " + transactions.size());

	    // Wallet과 Transaction 관계 설정
	    for (Transaction tx : transactions) {
	        if (!wallet.getTransactions().contains(tx)) wallet.addTransaction(tx);
	        if (tx.getTransfers() != null) tx.getTransfers().forEach(t -> t.setTransaction(tx));
	        if (!tx.getWallets().contains(wallet)) tx.getWallets().add(wallet);
	    }

	    // 저장: 모든 트랜잭션을 한번에 저장, Wallet 저장
	    transactionRepository.saveAll(transactions);
	    walletRepository.save(wallet);

	    Set<String> nextAddresses = new HashSet<>();
	    for (Transaction tx : transactions) {
	        if (tx.getTransfers() == null) continue;
	        tx.getTransfers().forEach(t -> {
	            if (t.getSender() != null && !visited.contains(t.getSender())) nextAddresses.add(t.getSender());
	            if (t.getReceiver() != null && !visited.contains(t.getReceiver())) nextAddresses.add(t.getReceiver());
	        });
	    }

	    for (String next : nextAddresses) {
	        traceTransactionsRecursive(next, depth + 1, maxDepth, visited);
	    }
	}

	@Transactional
	public void traceRecursiveDetailed(String address, int depth, int maxDepth,
	                                  Map<Integer, List<Wallet>> depthMap, Set<String> visited) {
	    if (depth > maxDepth || visited.contains(address)) return;
	    visited.add(address);
	    System.out.println("[traceDetailed] Depth: " + depth + ", Address: " + address);

	    Wallet wallet = walletRepository.findById(address)
	            .orElseGet(() -> walletRepository.save(new Wallet(address, 1, 0L)));

	    List<Transaction> transactions = getTransactions(address, 10);  // DB 조회된 Managed 객체여야 함

	    if (transactions == null || transactions.isEmpty()) {
	        System.out.println("[traceDetailed] No transactions for: " + address);
	        return;
	    }

	    System.out.println("[traceDetailed] Fetched transactions: " + transactions.size());

	    // wallet.getTransactions()에 있는 트랜잭션 ID 집합 생성 (중복 방지용)
	    Set<String> existingTxIDs = wallet.getTransactions().stream()
	            .map(Transaction::getTxID)
	            .collect(Collectors.toSet());

	    for (Transaction tx : transactions) {
	        if (!existingTxIDs.contains(tx.getTxID())) {
	            wallet.addTransaction(tx);
	        }
	        if (tx.getTransfers() != null) {
	            tx.getTransfers().forEach(t -> t.setTransaction(tx));
	        }
	        if (!tx.getWallets().contains(wallet)) {
	            tx.getWallets().add(wallet);
	        }
	    }

	    transactionRepository.saveAll(transactions);  // saveAll로 한 번에 저장
	    walletRepository.save(wallet);

	    depthMap.computeIfAbsent(depth, d -> new ArrayList<>()).add(wallet);

	    Set<String> nextAddresses = new HashSet<>();
	    for (Transaction tx : transactions) {
	        if (tx.getTransfers() == null) continue;
	        tx.getTransfers().forEach(t -> {
	            if (t.getSender() != null && !visited.contains(t.getSender())) nextAddresses.add(t.getSender());
	            if (t.getReceiver() != null && !visited.contains(t.getReceiver())) nextAddresses.add(t.getReceiver());
	        });
	    }

	    for (String next : nextAddresses) {
	        traceRecursiveDetailed(next, depth + 1, maxDepth, depthMap, visited);
	    }
	}


}
