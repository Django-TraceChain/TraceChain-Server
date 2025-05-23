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

/*
** 1. Relayer 후보선정: => 한 지갑이 여러 지갑으로 송금하면서도, 입금 내역이 없음, 트랜잭션 간의 시간 간격이 5분 이내
* 2. 수신자 주소들은 입금 이력이 없어야함: => 수신자는 다른 지갑에서 자금을 받은 적이 없고, 오직 해당 relayer에게서만 받음
* 3. 최소 3건이상 유사 트랜잭션일 경우 relayer 패턴으로 판단
*
* relay는 자신의 돈을 보내는 것이 아니라, 다른 사용자의 요청을 받고 그 대신 송금을 수행하는 역할을 한다.
* 이 때 입급은 Relayer가 직접 받는게 아니라, 스마트컨트랙트가 입금을 받고 출금 요청만 relayer가 수행한다.
* 따라서 Relayer는 입금 없이 출금만 있는 지갑이라는 독특한 패턴을 갖게됨.
*
* "이 지갑이 중간 전달자(relayer)로서 자금을 다른 지갑으로 보낸 적이 있는가?" 를 탐지하는는 믹싱패턴
* 📚 관련 논문 및 참고 문헌
다음은 실제 연구 및 분석에서 위 기준들이 어떻게 활용되는지를 보여주는 논문과 자료들이야:

[1] Detecting Ethereum Mixers (AUA, 2024)
Tornado Cash 등 믹서 컨트랙트의 함수 시그니처(deposit(bytes32))를 추적하여 사용 여부 탐지

Relayer를 통한 출금 구조 강조

📄 논문 보기

[2] Address Linkability in Tornado Cash (Springer, 2021)
주소 간의 linkability (연결 가능성)을 판단하는 휴리스틱 분석 제시

타이밍, 거래 패턴 기반으로 relayer 패턴 탐지

📄 논문 보기

[3] Correlating Accounts on Ethereum Mixing Services (arXiv, 2024)
다양한 계정을 연결짓기 위한 정량적 분석 프레임워크 제시

relayer 추론 및 지갑 연결성 분석 포함


 */

@Service
public class RelayerDetector implements MixingDetector {

    @Autowired
    private WalletRepository walletRepository;

    private static final int TIME_THRESHOLD_SEC = 300; // 5분
    private static final int MIN_RELAY_COUNT = 3;

    @Transactional
    @Override
    public void analyze(List<Wallet> wallets) {
        Map<String, List<Transfer>> senderToTransfers = new HashMap<>();

        System.out.println("[Relayer] 분석 시작");

        // 1. 모든 Transfer 수집
        for (Wallet wallet : wallets) {
            for (Transaction tx : wallet.getTransactions()) {
                for (Transfer t : tx.getTransfers()) {
                    String sender = t.getSender();
                    if (sender == null || sender.equals(wallet.getAddress())) continue;

                    senderToTransfers.computeIfAbsent(sender, k -> new ArrayList<>()).add(t);
                }
            }
        }

        System.out.println("[Relayer] 후보 relayer 수: " + senderToTransfers.size());

        // 2. 후보 Relayer를 검토
        for (Map.Entry<String, List<Transfer>> entry : senderToTransfers.entrySet()) {
            String potentialRelayer = entry.getKey();
            List<Transfer> transfers = entry.getValue();

            System.out.println("[Relayer] 후보 주소 검사 중: " + potentialRelayer);

            // 수신자별 그룹핑
            Map<String, List<Transfer>> receiverMap = new HashMap<>();
            for (Transfer t : transfers) {
                receiverMap.computeIfAbsent(t.getReceiver(), r -> new ArrayList<>()).add(t);
            }

            List<Transfer> recentTransfers = new ArrayList<>();
            for (List<Transfer> tList : receiverMap.values()) {
                recentTransfers.addAll(tList);
            }

            // 시간 기준 정렬
            recentTransfers.sort(Comparator.comparing(t -> t.getTransaction().getTimestamp()));

            // 3. 시간 간격 내 그룹핑 및 relayer 패턴 확인
            List<Transfer> group = new ArrayList<>();
            LocalDateTime baseTime = null;

            for (Transfer t : recentTransfers) {
                LocalDateTime tTime = t.getTransaction().getTimestamp();
                if (baseTime == null || Duration.between(baseTime, tTime).getSeconds() <= TIME_THRESHOLD_SEC) {
                    if (baseTime == null) baseTime = tTime;
                    group.add(t);
                } else {
                    baseTime = tTime;
                    group.clear();
                    group.add(t);
                }

                if (group.size() >= MIN_RELAY_COUNT) {
                    System.out.println("[Relayer] 시간 조건 충족: " + potentialRelayer + ", 트랜잭션 수=" + group.size());

                    // 수신자의 입금 이력 확인
                    boolean allReceiversHaveNoIncoming = group.stream()
                            .map(Transfer::getReceiver)
                            .distinct()
                            .allMatch(receiver -> wallets.stream()
                                    .noneMatch(w -> w.getAddress().equals(receiver) &&
                                            w.getTransactions().stream()
                                                    .flatMap(tx -> tx.getTransfers().stream())
                                                    .anyMatch(t2 -> receiver.equals(t2.getSender()))
                                    ));

                    if (allReceiversHaveNoIncoming) {
                        System.out.println("[Relayer] 수신자 조건 충족: " + potentialRelayer);

                        // 해당 relayer 주소로 등록된 모든 지갑에 패턴 표시
                        for (Wallet w : wallets) {
                            if (w.getAddress().equals(potentialRelayer)) {
                                w.setRelayerPattern(true);
                                w.setPatternCnt(w.getPatternCnt() + 1);
                                walletRepository.save(w);
                                System.out.println("[Relayer] 패턴 감지됨: " + potentialRelayer);
                            }
                        }
                        break;
                    } else {
                        System.out.println("[Relayer] 수신자 입금 이력 존재: " + potentialRelayer);
                    }
                }
            }
        }

        System.out.println("[Relayer] 분석 완료");
    }
}
