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
import java.util.stream.Collectors;

/**
 * 고정밀 Peel Chain 탐지기 (UTXO 완전모델 없이 근사)
 * - 지갑 기준 "송금 트랜잭션" 중에서 2개의 수신자(2-out)로 분배
 * - 한 출력은 '소액'(전체 송금 대비 비율 작음), 다른 한 출력은 '큰 변화(change)'로 간주
 * - 이런 단계가 연쇄로 이어지고(연속 길이), 큰 변화 값이 대체로 감소(또는 비증가)
 * - 단계 간 시간 간격이 너무 벌어지지 않음
 *
 * 점수:
 *   s_peel = 0.5*min(chainLen/Lmax,1) + 0.25*(1 - CV_small) + 0.25*decayRate
 *   (임계 0.70 권장)
 */
@Service
public class PeelChainDetector implements MixingDetector {

    @Autowired
    private WalletRepository walletRepository;

    private static final int    L_MIN = 4;           // 최소 연쇄 길이
    private static final int    L_MAX = 8;           // 점수 정규화용 상한
    private static final double SMALL_RATIO = 0.20;  // '소액' 비율 임계 (20%)
    private static final long   GAP_MAX_SEC = 24 * 3600; // 단계 간 최대 간격 24h
    private static final double THRESHOLD = 0.70;    // 최종 판정 임계

    static final class Stage {
        final String txid;
        final double small;   // 소액 출력
        final double large;   // 변화로 간주
        final long   tsSec;
        Stage(String id, double s, double l, long t) {
            this.txid = id; this.small = s; this.large = l; this.tsSec = t;
        }
    }

    @Transactional
    @Override
    public void analyze(List<Wallet> wallets) {
        if (wallets == null || wallets.isEmpty()) return;

        for (Wallet w : wallets) {
            List<Transaction> txs = w.getTransactions();
            if (txs == null || txs.isEmpty()) {
                w.setPeelChainPattern(false);
                walletRepository.save(w);
                continue;
            }

            // 시간순 정렬
            txs.sort(Comparator.comparing(Transaction::getTimestamp));

            // 이 지갑이 '보낸' 트랜잭션들을 추출
            List<Transaction> outTxs = txs.stream()
                    .filter(tx -> tx.getTransfers().stream().anyMatch(t -> w.getAddress().equals(t.getSender())))
                    .collect(Collectors.toList());

            // 각 트랜잭션에서 2-out + 소액 조건을 만족하는 단계만 뽑기
            List<Stage> stages = new ArrayList<>();
            for (Transaction tx : outTxs) {
                List<Transfer> outs = tx.getTransfers().stream()
                        .filter(t -> w.getAddress().equals(t.getSender()) && t.getReceiver() != null)
                        .collect(Collectors.toList());
                // 2개의 수신자만 고려 (1-in/2-out 근사)
                Set<String> uniqReceivers = outs.stream().map(Transfer::getReceiver).collect(Collectors.toSet());
                if (uniqReceivers.size() != 2) continue;

                double total = outs.stream()
                        .map(Transfer::getAmount)
                        .filter(Objects::nonNull)
                        .mapToDouble(BigDecimal::doubleValue).sum();
                if (total <= 0.0) continue;

                // 소액/대액 분리
                List<Double> amounts = outs.stream()
                        .map(Transfer::getAmount)
                        .filter(Objects::nonNull)
                        .map(BigDecimal::doubleValue)
                        .sorted()
                        .toList();
                if (amounts.size() < 2) continue;

                double small = amounts.get(0);
                double large = amounts.get(amounts.size() - 1);
                double ratio = small / total;

                if (ratio <= SMALL_RATIO) {
                    long ts = tx.getTimestamp().toEpochSecond(java.time.ZoneOffset.UTC);
                    stages.add(new Stage(tx.getTxID(), small, large, ts));
                }
            }

            // 시간 간격 제약으로 연쇄 연결
            List<Stage> chain = new ArrayList<>();
            List<Stage> bestChain = new ArrayList<>();
            for (Stage s : stages) {
                if (chain.isEmpty()) {
                    chain.add(s);
                    continue;
                }
                Stage prev = chain.get(chain.size() - 1);
                if ((s.tsSec - prev.tsSec) <= GAP_MAX_SEC) {
                    chain.add(s);
                } else {
                    if (chain.size() > bestChain.size()) bestChain = new ArrayList<>(chain);
                    chain.clear();
                    chain.add(s);
                }
            }
            if (chain.size() > bestChain.size()) bestChain = chain;
            chain = bestChain;

            boolean detected = false;
            double score = 0.0;

            if (chain.size() >= L_MIN) {
                // (a) 연속 길이 정규화
                double fLen = Math.min(1.0, chain.size() / (double) L_MAX) * 0.5;

                // (b) 소액 안정성: CV(small) 낮을수록 좋음
                double cvSmall = cv(chain.stream().map(st -> st.small).toList());
                double fSmall = (1.0 - clamp01(cvSmall)) * 0.25;

                // (c) 감쇠율: large 값이 비증가/완만감소일수록 점수↑
                double fDecay = decayFactor(chain) * 0.25;

                score = fLen + fSmall + fDecay;
                detected = (score >= THRESHOLD);
            }

            if (detected && !Boolean.TRUE.equals(w.getPeelChainPattern())) {
                w.setPatternCnt(w.getPatternCnt() + 1);
            }
            w.setPeelChainPattern(detected);
            walletRepository.save(w);
        }
    }

    private double cv(List<Double> vals) {
        if (vals == null || vals.size() <= 1) return 0.0;
        double mean = vals.stream().mapToDouble(x -> x).average().orElse(0.0);
        if (mean == 0.0) return 1.0;
        double var = 0.0;
        for (double v : vals) {
            double d = v - mean;
            var += d * d;
        }
        var /= (vals.size() - 1);
        return Math.sqrt(var) / mean;
    }

    private double decayFactor(List<Stage> chain) {
        if (chain.size() <= 1) return 0.5; // 정보 부족 → 중간값
        int nonIncreasing = 0;
        for (int i = 1; i < chain.size(); i++) {
            if (chain.get(i).large <= chain.get(i - 1).large) nonIncreasing++;
        }
        double ratio = nonIncreasing / (double) (chain.size() - 1);
        // 비증가 비율을 그대로 사용 (완벽 비증가=1, 절반만 비증가=0.5)
        return clamp01(ratio);
    }

    private double clamp01(double x) {
        if (x < 0.0) return 0.0;
        if (x > 1.0) return 1.0;
        return x;
    }
}
