package com.Django.TraceChain.service;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.WalletRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

@Service
public class FixedAmountDetector implements MixingDetector {

    @Autowired
    private WalletRepository walletRepository;

    // 파라미터
    private static final int WINDOW_SEC = 300;   // 5분
    private static final double EPS = 0.02;      // ±2%
    private static final int K = 3;              // 최소 반복
    private static final double TAU_H = 0.45;    // 정규화 엔트로피 임계

    // 정액 후보 (예시)
    private static final List<BigDecimal> DENOMS = List.of(
            new BigDecimal("0.1"),
            new BigDecimal("1"),
            new BigDecimal("10")
    );

    private double entropyNorm(Map<BigDecimal, Integer> hist) {
        int total = hist.values().stream().mapToInt(i -> i).sum();
        if (total == 0) return 1.0;
        int m = (int) hist.values().stream().filter(v -> v > 0).count();
        if (m <= 1) return 0.0;

        double H = 0.0;
        for (int c : hist.values()) {
            if (c <= 0) continue;
            double p = (double) c / total;
            H += -p * Math.log(p);
        }
        return H / Math.log(m);
    }

    @Transactional
    @Override
    public void analyze(List<Wallet> wallets) {
        for (Wallet wallet : wallets) {
            String addr = wallet.getAddress();
            List<Transaction> txs = wallet.getTransactions();
            if (txs == null || txs.isEmpty()) {
                wallet.setFixedAmountPattern(false);
                walletRepository.save(wallet);
                continue;
            }

            txs.sort(Comparator.comparing(Transaction::getTimestamp));
            boolean detected = false;

            for (int i = 0; i < txs.size(); i++) {
                Map<BigDecimal, Integer> hist = new HashMap<>();
                var start = txs.get(i).getTimestamp();

                for (int j = i; j < txs.size(); j++) {
                    var tx = txs.get(j);
                    if (Duration.between(start, tx.getTimestamp()).getSeconds() > WINDOW_SEC) break;

                    for (Transfer t : tx.getTransfers()) {
                        // 이 지갑이 "보낸" 출력 기준으로 카운트 (원하면 수신도 포함 가능)
                        if (!addr.equals(t.getSender())) continue;

                        BigDecimal v = t.getAmount();
                        for (BigDecimal d : DENOMS) {
                            BigDecimal lo = d.multiply(BigDecimal.valueOf(1 - EPS));
                            BigDecimal hi = d.multiply(BigDecimal.valueOf(1 + EPS));
                            if (v.compareTo(lo) >= 0 && v.compareTo(hi) <= 0) {
                                hist.merge(d, 1, Integer::sum);
                            }
                        }
                    }
                }

                if (hist.isEmpty()) continue;

                int fmax = hist.values().stream().mapToInt(x -> x).max().orElse(0);
                double Hn = entropyNorm(hist);

                if (fmax >= K && Hn <= TAU_H) {
                    detected = true;
                    break;
                }
            }

            if (detected && !Boolean.TRUE.equals(wallet.getFixedAmountPattern())) {
                wallet.setPatternCnt(wallet.getPatternCnt() + 1);
            }
            wallet.setFixedAmountPattern(detected);
            walletRepository.save(wallet);
        }
    }
}
