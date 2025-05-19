package com.Django.TraceChain;

import com.Django.TraceChain.model.*;
import com.Django.TraceChain.service.LoopingDetector;
import com.Django.TraceChain.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LoopingDetectorTest {

    @Test
    public void testSimpleLoopDetection() {
        // 🧪 Step 1: 가짜 Wallet/Transaction/Transfer 구성 (A → B → C → A)
        Wallet walletA = new Wallet("A", 1, 1000);
        Wallet walletB = new Wallet("B", 1, 1000);
        Wallet walletC = new Wallet("C", 1, 1000);

        // timestamp는 시간순 보장
        Transaction tx1 = new Transaction("tx1", 100, LocalDateTime.now());
        tx1.addTransfer(new Transfer(tx1, "A", "B", 100));

        Transaction tx2 = new Transaction("tx2", 100, LocalDateTime.now().plusSeconds(10));
        tx2.addTransfer(new Transfer(tx2, "B", "C", 100));

        Transaction tx3 = new Transaction("tx3", 100, LocalDateTime.now().plusSeconds(20));
        tx3.addTransfer(new Transfer(tx3, "C", "A", 100));  // A로 다시 돌아옴 → 루프

        // 트랜잭션 할당
        walletA.setTransactions(List.of(tx1, tx3));
        walletB.setTransactions(List.of(tx2));
        walletC.setTransactions(List.of());

        // 🧪 Step 2: LoopingDetector 실행
        WalletRepository mockRepo = Mockito.mock(WalletRepository.class);
        LoopingDetector detector = new LoopingDetector();

        // 리플렉션으로 walletRepository 필드 주입
        try {
            var field = LoopingDetector.class.getDeclaredField("walletRepository");
            field.setAccessible(true);
            field.set(detector, mockRepo);
        } catch (Exception e) {
            fail("Reflection injection failed: " + e.getMessage());
        }

        detector.analyze(List.of(walletA, walletB, walletC));

        // 🧪 Step 3: 결과 확인
        assertTrue(walletA.getLoopingPattern(), "Wallet A should have a detected loop.");
        assertEquals(1, walletA.getPatternCnt(), "Wallet A should have 1 pattern detected.");
    }
}
