package com.Django.TraceChain.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.WalletRepository;

@Service
public class PeelChainDetector implements MixingDetector {

    private static final int CHAIN_LENGTH_THRESHOLD = 3; // 최소 3회 연속
    private static final double SMALL_AMOUNT_RATIO = 0.05; // 전체 금액의 5% 이하일 때 '소액'으로 판단

    @Autowired
    private WalletRepository walletRepository;

    @Transactional
    @Override
    public void analyze(List<Wallet> wallets) {
        for (Wallet wallet : wallets) {
            String walletAddress = wallet.getAddress();
            System.out.println("[PeelChain] 분석 시작: " + walletAddress);

            List<Transaction> transactions = wallet.getTransactions();
            transactions.sort(Comparator.comparing(Transaction::getTimestamp));

            Map<String, Transaction> txById = new HashMap<>();
            for (Transaction tx : transactions) {
                txById.put(tx.getTxID(), tx);
            }

            int chainLength = 0;
            boolean detected = false;

            for (Transaction tx : transactions) {
                List<Transfer> transfers = tx.getTransfers();

                Set<String> inputAddrs = new HashSet<>();
                Set<String> outputAddrs = new HashSet<>();
                BigDecimal totalInput = BigDecimal.ZERO;
                BigDecimal totalOutput = BigDecimal.ZERO;
                BigDecimal smallAmount = BigDecimal.ZERO;

                for (Transfer t : transfers) {
                    if (t.getSender() != null && t.getSender().equals(walletAddress)) {
                        inputAddrs.add(t.getSender());
                        totalInput = totalInput.add(t.getAmount());
                    }
                    if (t.getReceiver() != null) {
                        outputAddrs.add(t.getReceiver());
                        totalOutput = totalOutput.add(t.getAmount());
                    }
                }

                if (inputAddrs.size() == 1 && outputAddrs.size() == 2) {
                    for (Transfer t : transfers) {
                        if (t.getSender().equals(walletAddress) &&
                            t.getAmount().compareTo(totalInput.multiply(BigDecimal.valueOf(SMALL_AMOUNT_RATIO))) <= 0) {
                            smallAmount = t.getAmount();
                        }
                    }

                    if (smallAmount.compareTo(BigDecimal.ZERO) > 0) {
                        chainLength++;
                    } else {
                        chainLength = 0;
                    }

                    if (chainLength >= CHAIN_LENGTH_THRESHOLD) {
                        detected = true;
                        break;
                    }
                } else {
                    chainLength = 0;
                }
            }

            wallet.setPeelChainPattern(detected);
            if (detected) {
                wallet.setPatternCnt(wallet.getPatternCnt() + 1);
                System.out.println("[PeelChain] 패턴 감지됨: " + walletAddress);
            } else {
                System.out.println("[PeelChain] 패턴 없음: " + walletAddress);
            }

            // DB 반영
            walletRepository.save(wallet);
        }
    }
}
