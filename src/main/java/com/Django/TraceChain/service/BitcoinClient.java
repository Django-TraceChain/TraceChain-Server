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

		Optional<Transaction> existing = transactionRepository.findById(txid);
		if (existing.isPresent()) return existing.get();

		long amount = 0;
		for (JsonNode vout : txNode.path("vout")) {
			amount += (long) vout.path("value").asDouble();
		}
		LocalDateTime txTime = LocalDateTime.ofInstant(
				Instant.ofEpochSecond(txNode.path("status").path("block_time").asLong()), ZoneOffset.UTC);

		Transaction tx = new Transaction(txid, amount, txTime);

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

		transactionRepository.save(tx);
		return tx;
	}

	@Override
	public List<Transaction> getTransactions(String address) {
		return getTransactions(address, 10);
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

				Transaction tx = createTransaction(txNode, address);
				if (tx == null) continue; // already exists

				transactionMap.put(tx.getTxID(), tx);
				tx.getWallets().add(wallet);
				wallet.addTransaction(tx);
				count++;
			}
		} catch (Exception e) {
			System.out.println("Bitcoin getTransactions error: " + e.getMessage());
		}

		walletRepository.save(wallet);
		return new ArrayList<>(transactionMap.values());
	}

	@Transactional
	public void traceTransactionsRecursive(String address, int depth, int maxDepth, Set<String> visited) {
		if (depth > maxDepth || visited.contains(address)) return;
		visited.add(address);

		Wallet wallet = walletRepository.findById(address).orElseGet(() -> findAddress(address));
		if (wallet == null) return;
		List<Transaction> transactions = getTransactions(address);
		if (transactions == null || transactions.isEmpty()) return;

		Map<String, Transaction> txMap = new LinkedHashMap<>();
		for (Transaction tx : transactions) txMap.put(tx.getTxID(), tx);
		transactionRepository.saveAll(new ArrayList<>(txMap.values()));
		walletRepository.save(wallet);

		Set<String> nextAddresses = new HashSet<>();
		for (Transaction tx : txMap.values()) {
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

		Wallet wallet = walletRepository.findById(address)
				.orElseGet(() -> walletRepository.save(new Wallet(address, 1, 0L)));
		if (wallet == null) return;

		List<Transaction> transactions = getTransactions(address, 10);
		if (transactions == null || transactions.isEmpty()) return;

		Map<String, Transaction> txMap = new LinkedHashMap<>();
		for (Transaction tx : transactions) txMap.put(tx.getTxID(), tx);
		transactionRepository.saveAll(new ArrayList<>(txMap.values()));
		walletRepository.save(wallet);

		depthMap.computeIfAbsent(depth, d -> new ArrayList<>()).add(wallet);

		Set<String> nextAddresses = new HashSet<>();
		for (Transaction tx : txMap.values()) {
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