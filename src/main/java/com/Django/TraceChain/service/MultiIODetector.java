package com.Django.TraceChain.service;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.WalletRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
public class MultiIODetector implements MixingDetector {

    @Autowired
    private WalletRepository walletRepository;

    private double cv(List<BigDecimal> vals) {
        if (vals == null || vals.size() <= 1) return 0.0;
        double mean = vals.stream().mapToDouble(v -> v.doubleValue()).average().orElse(0.0);
        if (mean == 0.0) return 1.0;
        double var = 0.0;
        for (BigDecimal v : vals) {
            double d = v.doubleValue() - mean;
            var += d * d;
        }
        var /= (vals.size() - 1);
        return Math.sqrt(var) / mean;
    }

    @Transactional
    @Override
    public void analyze(List<Wallet> wallets) {
        for (Wallet wallet : wallets) {
            boolean detected = false;

            List<Transaction> txs = wallet.getTransactions();
            if (txs == null || txs.isEmpty()) {
                wallet.setMultiIOPattern(false);
                walletRepository.save(wallet);
                continue;
            }

            for (Transaction tx : txs) {
                Set<String> ins = new HashSet<>();
                Set<String> outs = new HashSet<>();
                List<BigDecimal> outAmounts = new ArrayList<>();

                for (Transfer t : tx.getTransfers()) {
                    if (t.getSender() != null) ins.add(t.getSender());
                    if (t.getReceiver() != null) {
                        outs.add(t.getReceiver());
                        outAmounts.add(t.getAmount());
                    }
                }

                // 다중 입/출력 + 출력 금액 균질성
                if (ins.size() >= 3 && outs.size() >= 3) {
                    double cvOut = cv(outAmounts);
                    if (cvOut <= 0.30) { // 임계값 예시
                        detected = true;
                        break;
                    }
                }
            }

            if (detected && !Boolean.TRUE.equals(wallet.getMultiIOPattern())) {
                wallet.setPatternCnt(wallet.getPatternCnt() + 1);
            }
            wallet.setMultiIOPattern(detected);
            walletRepository.save(wallet);
        }
    }
}
