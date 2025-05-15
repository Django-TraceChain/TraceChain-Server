package com.Django.TraceChain.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.WalletRepository;

@Service
public class FixedAmountDetector implements MixingDetector {

    @Autowired
    private WalletRepository walletRepository;

    @Override
    public void analyze(List<Wallet> wallets) {
        for (Wallet wallet : wallets) {
            List<Transaction> transactions = wallet.getTransactions();
            String walletAddress = wallet.getAddress();

            // timestamp 기준 정렬
            transactions.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));

            boolean detected = false;

            for (int i = 0; i < transactions.size(); i++) {
                Map<Double, Integer> amountCountMap = new HashMap<>(); // 금액별 출현 횟수 저장
                LocalDateTime startTime = transactions.get(i).getTimestamp();

                for (int j = i; j < transactions.size(); j++) {
                    Transaction currentTx = transactions.get(j);
                    LocalDateTime currentTime = currentTx.getTimestamp();

                    if (Duration.between(startTime, currentTime).getSeconds() > 300) break; // 5분 초과

                    for (Transfer transfer : currentTx.getTransfers()) {
                        if (walletAddress.equals(transfer.getSender()) || walletAddress.equals(transfer.getReceiver())) {
                            double amount = transfer.getAmount(); // 금액 가져오기
                            amountCountMap.put(amount, amountCountMap.getOrDefault(amount, 0) + 1);
                        }
                    }
                }

                // 고정된 금액으로 최소 3회 이상 입출력 반복 시 탐지
                for (int count : amountCountMap.values()) {
                    if (count >= 3) {
                        detected = true;
                        break;
                    }
                }

                if (detected) break;
            }

            wallet.setFixedAmountPattern(detected);
            if (detected) {
                wallet.setPatternCnt(wallet.getPatternCnt() + 1);
            }
            walletRepository.save(wallet);
        }
    }
}
