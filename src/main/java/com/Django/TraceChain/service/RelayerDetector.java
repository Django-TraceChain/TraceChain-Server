package com.Django.TraceChain.service;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.WalletRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 고정밀 Relayer 탐지기
 * - 시간창(5분) 내 동일 발신자(sender)가 다수 수신자에게 연속 출금
 * - 금액 '정액'(denom) 일관성(±2%)
 * - 출금 간 인터벌의 변동계수(CV) 낮음 → 자동화 의심
 * - 수신자 과거 이력 없음(무-history) → 새 엔티티로 지급
 *
 * 점수:
 *   s_rel = min(1, 0.4*1[Nr>=3] + 0.3*1[denomConsistent] + 0.2*(1 - cvInterval) + 0.1*1[allReceiversNoHistory])
 *   (임계 0.70 권장)
 */
@Service
public class RelayerDetector implements MixingDetector {

    @Autowired
    private WalletRepository walletRepository;

    private static final int WINDOW_SEC = 300;     // 5분
    private static final int MIN_COUNT = 3;        // 최소 군집 크기
    private static final double EPS_DENOM = 0.02;  // ±2% 정액 허용
    private static final double THRESHOLD = 0.70;  // 최종 판정 임계

    static final class T {
        final String sender;
        final String receiver;
        final double amount;          // BigDecimal -> double로 계산 편의
        final LocalDateTime ts;
        final String txid;
        T(String s, String r, double a, LocalDateTime t, String id) {
            this.sender = s; this.receiver = r; this.amount = a; this.ts = t; this.txid = id;
        }
    }

    @Transactional
    @Override
    public void analyze(List<Wallet> wallets) {
        if (wallets == null || wallets.isEmpty()) return;

        // 전체 트랜잭션에서 Transfer 평탄화 (지갑 로컬 DB 기준)
        List<T> all = new ArrayList<>();
        for (Wallet w : wallets) {
            List<Transaction> txs = w.getTransactions();
            if (txs == null) continue;
            for (Transaction tx : txs) {
                LocalDateTime ts = tx.getTimestamp();
                for (Transfer tr : tx.getTransfers()) {
                    if (tr.getSender() == null || tr.getReceiver() == null) continue;
                    all.add(new T(tr.getSender(), tr.getReceiver(),
                            tr.getAmount() == null ? 0.0 : tr.getAmount().doubleValue(),
                            ts, tx.getTxID()));
                }
            }
        }
        if (all.isEmpty()) return;

        // sender별 시간 정렬
        Map<String, List<T>> bySender = all.stream()
                .collect(Collectors.groupingBy(t -> t.sender));
        bySender.values().forEach(list -> list.sort(Comparator.comparing(t -> t.ts)));

        // 모든 지갑은 초기 false로
        wallets.forEach(w -> w.setRelayerPattern(false));

        for (Map.Entry<String, List<T>> e : bySender.entrySet()) {
            String candidate = e.getKey(); // 잠재적 relayer
            List<T> txs = e.getValue();
            if (txs.size() < MIN_COUNT) continue;

            // 슬라이딩 윈도우 군집
            int left = 0;
            while (left < txs.size()) {
                int right = left;
                LocalDateTime base = txs.get(left).ts;
                List<T> group = new ArrayList<>();
                while (right < txs.size() &&
                        Duration.between(base, txs.get(right).ts).getSeconds() <= WINDOW_SEC) {
                    group.add(txs.get(right));
                    right++;
                }

                // 군집 평가
                double score = scoreGroup(candidate, group, all, base);
                if (score >= THRESHOLD) {
                    // relayer 지갑 객체 찾아 표시
                    for (Wallet w : wallets) {
                        if (candidate.equals(w.getAddress())) {
                            if (!Boolean.TRUE.equals(w.getRelayerPattern())) {
                                w.setPatternCnt(w.getPatternCnt() + 1);
                            }
                            w.setRelayerPattern(true);
                            walletRepository.save(w);
                        }
                    }
                }

                // 다음 윈도우
                left = Math.max(left + 1, right == left ? left + 1 : right);
            }
        }
    }

    private double scoreGroup(String sender, List<T> group, List<T> all, LocalDateTime base) {
        if (group == null || group.size() < MIN_COUNT) return 0.0;

        // 1) 수량 Nr
        int Nr = group.size();
        double fCount = (Nr >= MIN_COUNT) ? 1.0 : 0.0;

        // 2) 정액 일관성 (max/min ≤ 1+ε)
        double max = group.stream().mapToDouble(t -> t.amount).max().orElse(0.0);
        double min = group.stream().mapToDouble(t -> t.amount).min().orElse(0.0);
        boolean denomConsistent = (min > 0.0) && (max / min <= (1.0 + EPS_DENOM));
        double fDenom = denomConsistent ? 1.0 : 0.0;

        // 3) 인터벌 CV (낮을수록 좋음 → 1 - CV)
        double cvInt = cvOfIntervals(group.stream().map(t -> t.ts).sorted().toList());
        double fInterval = clamp01(1.0 - cvInt); // CV 0이면 1점, CV 1이면 0점 (대략)

        // 4) 수신자 무-히스토리: base 이전에 어떤 입출력 기록도 없는 fresh 주소인지
        //    (이 구현은 "DB에 들어온 범위 내에서" 과거 기록이 없음을 뜻함. 실제 '무-deposit' 근사치)
        Set<String> receivers = group.stream().map(t -> t.receiver).collect(Collectors.toSet());
        boolean allFresh = receivers.stream().allMatch(r -> noHistoryBefore(r, base, all));
        double fNoHist = allFresh ? 1.0 : 0.0;

        // s_rel
        double s = 0.4 * fCount + 0.3 * fDenom + 0.2 * fInterval + 0.1 * fNoHist;
        return Math.min(1.0, s);
    }

    private boolean noHistoryBefore(String addr, LocalDateTime base, List<T> universe) {
        for (T t : universe) {
            if (t.ts.isBefore(base) && (addr.equals(t.sender) || addr.equals(t.receiver))) {
                return false;
            }
        }
        return true;
    }

    private double cvOfIntervals(List<LocalDateTime> times) {
        if (times.size() <= 2) return 0.0; // 인터벌 1개 이하면 CV=0 취급
        List<Long> deltas = new ArrayList<>();
        for (int i = 1; i < times.size(); i++) {
            deltas.add(Duration.between(times.get(i - 1), times.get(i)).getSeconds());
        }
        double mean = deltas.stream().mapToDouble(x -> x).average().orElse(0.0);
        if (mean == 0.0) return 1.0;
        double var = 0.0;
        for (long d : deltas) {
            double diff = d - mean;
            var += diff * diff;
        }
        var /= (deltas.size() - 1);
        return Math.sqrt(var) / mean;
    }

    private double clamp01(double x) {
        if (x < 0.0) return 0.0;
        if (x > 1.0) return 1.0;
        return x;
    }
}
