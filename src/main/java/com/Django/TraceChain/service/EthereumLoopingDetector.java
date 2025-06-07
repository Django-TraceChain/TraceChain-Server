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
public class EthereumLoopingDetector implements MixingDetector {

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private static final int MAX_DEPTH = 4;

    @Override
    public void analyze(List<Wallet> wallets) {
        List<Wallet> ethWallets = wallets.stream()
                .filter(w -> w.getType() == 2) // ✅ Ethereum 지갑 필터
                .collect(Collectors.toList());

        Map<String, Set<String>> graph = buildGraph(ethWallets);

        for (Wallet wallet : ethWallets) {
            String start = wallet.getAddress();
            System.out.println("[EthereumLooping] 분석 시작: " + start);

            Set<String> visited = new HashSet<>();
            List<String> path = new ArrayList<>();
            List<List<String>> loopPaths = new ArrayList<>();

            boolean detected = findLoops(start, start, graph, visited, path, loopPaths, 0);

            if (detected && !Boolean.TRUE.equals(wallet.getLoopingPattern())) {
                Integer count = Optional.ofNullable(wallet.getPatternCnt()).orElse(0);
                wallet.setPatternCnt(count + 1);
                wallet.setLoopingPattern(true);

                System.out.println("[EthereumLooping] 루프 감지됨: " + start);
                loopPaths.forEach(lp ->
                        System.out.println("[EthereumLooping] 루프 경로: " + String.join(" → ", lp))
                );

                // 최근 트랜잭션 10개로 축소
                List<Transaction> originalTransactions = wallet.getTransactions();
                if (originalTransactions != null && !originalTransactions.isEmpty()) {
                    List<Transaction> limited = originalTransactions.stream()
                            .sorted(Comparator.comparing(Transaction::getTimestamp).reversed())
                            .limit(10)
                            .map(tx -> transactionRepository.findById(tx.getTxID()).orElse(null))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                    wallet.setTransactions(limited);
                }

                System.out.println("[EthereumLooping] 저장 전 패턴 상태: " + wallet.getLoopingPattern());
                walletRepository.save(wallet);
            } else if (!detected) {
                wallet.setLoopingPattern(false);
                walletRepository.save(wallet);
                System.out.println("[EthereumLooping] 루프 없음: " + start);
            }
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

    private boolean findLoops(String start, String current,
                              Map<String, Set<String>> graph,
                              Set<String> visited,
                              List<String> path,
                              List<List<String>> foundLoops,
                              int depth) {
        if (depth > MAX_DEPTH) return false;

        visited.add(current);
        path.add(current);

        for (String neighbor : graph.getOrDefault(current, Collections.emptySet())) {
            if (neighbor.equals(start) && path.size() > 1) {
                foundLoops.add(new ArrayList<>(path));
                return true;
            } else if (!visited.contains(neighbor)) {
                boolean found = findLoops(start, neighbor, graph,
                        new HashSet<>(visited),
                        new ArrayList<>(path),
                        foundLoops,
                        depth + 1);
                if (found) return true;
            }
        }
        return false;
    }
}
