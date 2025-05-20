package com.Django.TraceChain.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.WalletRepository;

@Service
public class FixedAmountDetector implements MixingDetector {

    @Autowired
    private WalletRepository walletRepository;

    @Transactional
    @Override
    public void analyze(List<Wallet> wallets) {
        for (Wallet wallet : wallets) {
            String walletAddress = wallet.getAddress();
            System.out.println("[FixedAmount] 분석 시작: " + walletAddress);

            List<Transaction> transactions = wallet.getTransactions();

            transactions.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));

            boolean detected = false;

            for (int i = 0; i < transactions.size(); i++) {
                Map<Double, Integer> amountCountMap = new HashMap<>();
                LocalDateTime startTime = transactions.get(i).getTimestamp();

                for (int j = i; j < transactions.size(); j++) {
                    Transaction currentTx = transactions.get(j);
                    LocalDateTime currentTime = currentTx.getTimestamp();

                    if (Duration.between(startTime, currentTime).getSeconds() > 300) break;

                    for (Transfer transfer : currentTx.getTransfers()) {
                        if (walletAddress.equals(transfer.getSender()) || walletAddress.equals(transfer.getReceiver())) {
                            double amount = transfer.getAmount();
                            amountCountMap.put(amount, amountCountMap.getOrDefault(amount, 0) + 1);
                        }
                    }
                }

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
                System.out.println("[FixedAmount] 패턴 감지됨: " + walletAddress);
            } else {
                System.out.println("[FixedAmount] 패턴 없음: " + walletAddress);
            }

            walletRepository.save(wallet);
        }
    }
}
