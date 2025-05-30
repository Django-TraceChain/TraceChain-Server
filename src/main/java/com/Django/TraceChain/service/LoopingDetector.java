package com.Django.TraceChain.service;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.TransactionRepository;
import com.Django.TraceChain.repository.WalletRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class LoopingDetector implements MixingDetector {

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private static final int MAX_DEPTH = 5;

    @Override
    public void analyze(List<Wallet> wallets) {
        Map<String, Set<String>> graph = buildGraph(wallets);

        for (Wallet wallet : wallets) {
            String start = wallet.getAddress();
            System.out.println("[Looping] 분석 시작: " + start);

            Set<String> visited = new HashSet<>();
            List<String> path = new ArrayList<>();
            List<List<String>> loopPaths = new ArrayList<>();

            findLoops(start, start, graph, visited, path, loopPaths, 0);

            boolean detected = !loopPaths.isEmpty();

            if (detected && !Boolean.TRUE.equals(wallet.getLoopingPattern())) {
                wallet.setPatternCnt(wallet.getPatternCnt() + 1);
                wallet.setLoopingPattern(true);

                System.out.println("[Looping] 패턴 감지됨: " + start);
                System.out.println("[Looping] 루프 개수: " + loopPaths.size());
                loopPaths.forEach(lp ->
                    System.out.println("[Looping] 루프 경로: " + String.join(" → ", lp))
                );

                List<Transaction> originalTransactions = wallet.getTransactions();
                if (originalTransactions != null && !originalTransactions.isEmpty()) {
                    List<Transaction> limited = originalTransactions.stream()
                            .sorted(Comparator.comparing(Transaction::getTimestamp).reversed())
                            .limit(10)
                            .map(tx -> transactionRepository.findById(tx.getTxID()).orElse(null))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toCollection(ArrayList::new));

                    wallet.setTransactions(limited);
                }

            } else if (!detected) {
                wallet.setLoopingPattern(false);
                System.out.println("[Looping] 패턴 없음: " + start);
            }

            walletRepository.save(wallet);
        }
    }

    private Map<String, Set<String>> buildGraph(List<Wallet> wallets) {
        Map<String, Set<String>> graph = new HashMap<>();

        for (Wallet wallet : wallets) {
            List<Transaction> transactions = wallet.getTransactions();
            if (transactions == null) continue;
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
