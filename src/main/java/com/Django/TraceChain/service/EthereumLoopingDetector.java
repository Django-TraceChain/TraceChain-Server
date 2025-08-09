package com.Django.TraceChain.service;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.WalletRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class EthereumLoopingDetector implements MixingDetector {

    @Autowired
    private WalletRepository walletRepository;

    private static final int MAX_DEPTH = 4;

    static class Edge {
        final String to;
        final LocalDateTime ts;
        Edge(String to, LocalDateTime ts) { this.to = to; this.ts = ts; }
    }

    private Map<String, List<Edge>> buildTimeGraph(List<Wallet> wallets) {
        Map<String, List<Edge>> g = new HashMap<>();

        // 이더리움 지갑만
        List<Wallet> ethWallets = wallets.stream()
                .filter(w -> w.getType() == 2)
                .toList();

        for (Wallet w : ethWallets) {
            List<Transaction> txs = w.getTransactions();
            if (txs == null) continue;
            txs.sort(Comparator.comparing(Transaction::getTimestamp));
            for (Transaction tx : txs) {
                LocalDateTime ts = tx.getTimestamp();
                for (Transfer t : tx.getTransfers()) {
                    String s = t.getSender(), r = t.getReceiver();
                    if (s == null || r == null || s.equals(r)) continue;
                    g.computeIfAbsent(s, k -> new ArrayList<>()).add(new Edge(r, ts));
                }
            }
        }
        return g;
    }

    private boolean dfs(String start, String cur, LocalDateTime lastTs,
                        Map<String, List<Edge>> g, Set<String> vis, List<String> path, int depth) {
        if (depth > MAX_DEPTH) return false;
        vis.add(cur);
        path.add(cur);

        for (Edge e : g.getOrDefault(cur, Collections.emptyList())) {
            if (lastTs != null && !e.ts.isAfter(lastTs)) continue; // 시간 단조 증가

            if (e.to.equals(start) && path.size() >= 3) {
                return true;
            }
            if (!vis.contains(e.to)) {
                if (dfs(start, e.to, e.ts, g, new HashSet<>(vis), new ArrayList<>(path), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Transactional
    @Override
    public void analyze(List<Wallet> wallets) {
        Map<String, List<Edge>> g = buildTimeGraph(wallets);

        for (Wallet wallet : wallets) {
            if (wallet.getType() != 2) continue; // 이더리움만
            String start = wallet.getAddress();
            boolean detected = dfs(start, start, null, g, new HashSet<>(), new ArrayList<>(), 0);

            if (detected && !Boolean.TRUE.equals(wallet.getLoopingPattern())) {
                wallet.setPatternCnt(wallet.getPatternCnt() + 1);
            }
            wallet.setLoopingPattern(detected);
            walletRepository.save(wallet);
        }
    }
}
