package com.Django.TraceChain.service;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.WalletRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class LoopingDetector implements MixingDetector {

	@Autowired
	private WalletRepository walletRepository;

	private static final int MAX_DEPTH = 5;

	@Override
	public void analyze(List<Wallet> wallets) {
		Map<String, Set<String>> graph = buildGraph(wallets);

		for (Wallet wallet : wallets) {
			String start = wallet.getAddress();
			Set<String> visited = new HashSet<>();
			List<String> path = new ArrayList<>();
			List<List<String>> loopPaths = new ArrayList<>();

			findLoops(start, start, graph, visited, path, loopPaths, 0);

			if (!loopPaths.isEmpty() && !Boolean.TRUE.equals(wallet.getLoopingPattern())) {
				wallet.setLoopingPattern(true);
				wallet.setPatternCnt(wallet.getPatternCnt() + 1);

				System.out.println("▶ LOOP DETECTED for wallet: " + start);
				System.out.println("▶ Loop count: " + loopPaths.size());
				loopPaths.forEach(lp ->
						System.out.println("▶ Loop path: " + String.join(" → ", lp))
				);

				walletRepository.saveAndFlush(wallet);
			}
		}
	}

	private Map<String, Set<String>> buildGraph(List<Wallet> wallets) {
		Map<String, Set<String>> graph = new HashMap<>();

		for (Wallet wallet : wallets) {
			List<Transaction> transactions = wallet.getTransactions();
			transactions.sort(Comparator.comparing(Transaction::getTimestamp));

			for (Transaction tx : transactions) {
				for (Transfer transfer : tx.getTransfers()) {
					String sender = transfer.getSender();
					String receiver = transfer.getReceiver();
					if (sender == null || receiver == null || sender.equals(receiver)) continue;

					graph.computeIfAbsent(sender, k -> new HashSet<>()).add(receiver);
				}
			}
		}

		return graph;
	}

	private void findLoops(String start, String current,
						   Map<String, Set<String>> graph,
						   Set<String> visited,
						   List<String> path,
						   List<List<String>> foundLoops,
						   int depth) {
		if (depth > MAX_DEPTH) return;

		visited.add(current);
		path.add(current);

		for (String neighbor : graph.getOrDefault(current, Collections.emptySet())) {
			if (neighbor.equals(start) && path.size() > 1) {
				foundLoops.add(new ArrayList<>(path));
			} else if (!visited.contains(neighbor)) {
				findLoops(start, neighbor, graph, new HashSet<>(visited), new ArrayList<>(path), foundLoops, depth + 1);
			}
		}
	}
}
