package com.Django.TraceChain.component;

import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.repository.WalletRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DatabaseInitializer implements CommandLineRunner {

    private final WalletRepository walletRepository;

    public DatabaseInitializer(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Override
    public void run(String... args) {
        // 1) 기본 Wallet 목록 생성
        List<Wallet> wallets = Arrays.asList(
            new Wallet("A", 1, 1000L),
            new Wallet("B", 1, 1000L),
            new Wallet("C", 1, 1000L),
            new Wallet("D", 1, 1000L),
            new Wallet("E", 1, 1000L),
            new Wallet("F", 1, 1000L),
            new Wallet("G", 1, 1000L),
            new Wallet("H", 1, 1000L),
            new Wallet("I", 1, 1000L),
            new Wallet("J", 1, 1000L),
            new Wallet("K", 1, 1000L),
            new Wallet("L", 1, 1000L)
        );

        // 2) 각 지갑별로 초기 넣을 트랜잭션 목록 정의
        Map<String, List<Transaction>> initialTxs = new HashMap<>();
        initialTxs.put("A", Arrays.asList(
            new Transaction("A_TX1",  15L, LocalDateTime.now()	),
            new Transaction("A_TX2",  50L, LocalDateTime.now())
        ));
        initialTxs.put("B", Arrays.asList(
            new Transaction("B_TX1", 5L, LocalDateTime.now())
        ));
        initialTxs.put("C", Arrays.asList(
                new Transaction("C_TX1", 10L, LocalDateTime.now()),
                new Transaction("C_TX2", 5L, LocalDateTime.now())
            ));
        initialTxs.put("D", Arrays.asList(
                new Transaction("D_TX1", 10L, LocalDateTime.now())
            ));
        initialTxs.put("E", Arrays.asList(
                new Transaction("E_TX1", 10L, LocalDateTime.now())
            ));
        initialTxs.put("F", Arrays.asList(
                new Transaction("F_TX1", 10L, LocalDateTime.now()),
                new Transaction("F_TX2", 15L, LocalDateTime.now())
            ));
        initialTxs.put("G", Arrays.asList(
                new Transaction("G_TX1", 10L, LocalDateTime.now())
            ));
        initialTxs.put("H", Arrays.asList(
                new Transaction("H_TX1", 10L, LocalDateTime.now()),
                new Transaction("H_TX2", 20L, LocalDateTime.now())
            ));
        initialTxs.put("I", Arrays.asList(
                new Transaction("I_TX1", 10L, LocalDateTime.now())
            ));
        initialTxs.put("J", Arrays.asList(
                new Transaction("J_TX1", 10L, LocalDateTime.now())
            ));
        initialTxs.put("K", Arrays.asList(
                new Transaction("K_TX1", 15L, LocalDateTime.now())
            ));
        initialTxs.put("L", Arrays.asList(
                new Transaction("L_TX1", 20L, LocalDateTime.now())
            ));
        
        // 3) Transaction별 Transfer(입출력) 정의
        // TransferData는 Transfer 생성자 인자를 담는 단순 DTO
        class TransferData { 
            String sender, receiver; long amount;
            TransferData(String s, String r, long a) { sender=s; receiver=r; amount=a; }
        }

        Map<String, List<TransferData>> txTransfers = new HashMap<>();
        txTransfers.put("A_TX1", Arrays.asList(
            new TransferData("A", "B", 5L),
            new TransferData("A", "C", 5L),
            new TransferData("A", "D", 5L)
        ));
        txTransfers.put("A_TX2", Arrays.asList(
            new TransferData("F", "A", 10L),
            new TransferData("G", "A", 10L),
            new TransferData("H", "A", 10L),
            new TransferData("I", "A", 10L),
            new TransferData("J", "A", 10L)
        ));
        txTransfers.put("B_TX1", Arrays.asList(
                new TransferData("B", "A", 5L)
            ));
        txTransfers.put("C_TX1", Arrays.asList(
                new TransferData("C", "E", 10L)
            ));
        txTransfers.put("C_TX2", Arrays.asList(
                new TransferData("A", "C", 5L)
            ));
        txTransfers.put("D_TX1", Arrays.asList(
                new TransferData("A", "D", 5L)
            ));
        txTransfers.put("E_TX1", Arrays.asList(
                new TransferData("C", "E", 10L)
            ));
        txTransfers.put("F_TX1", Arrays.asList(
                new TransferData("F", "A", 10L)
            ));
        txTransfers.put("F_TX2", Arrays.asList(
                new TransferData("K", "F", 15L)
            ));
        txTransfers.put("G_TX1", Arrays.asList(
                new TransferData("G", "A", 10L)
            ));
        txTransfers.put("H_TX1", Arrays.asList(
                new TransferData("H", "A", 10L)
            ));
        txTransfers.put("H_TX2", Arrays.asList(
                new TransferData("L", "H", 20L)
            ));
        txTransfers.put("I_TX1", Arrays.asList(
                new TransferData("I", "A", 10L)
            ));
        txTransfers.put("J_TX1", Arrays.asList(
                new TransferData("J", "A", 10L)
            ));
        txTransfers.put("K_TX1", Arrays.asList(
                new TransferData("K", "F", 15L)
            ));
        txTransfers.put("L_TX1", Arrays.asList(
                new TransferData("L", "H", 20L)
            ));

        // 4) 매핑 후 저장
        for (Wallet w : wallets) {
            // Wallet → Transactions
            List<Transaction> txs = initialTxs.getOrDefault(
                w.getAddress(), Collections.emptyList()
            );
            for (Transaction tx : txs) {
                // 4-1) Wallet ↔ Transaction 연관관계 설정
                w.addTransaction(tx);

                // 4-2) Transaction ↔ Transfers 연관관계 설정
                List<TransferData> tds = txTransfers.getOrDefault(
                    tx.getTxID(), Collections.emptyList()
                );
                for (TransferData td : tds) {
                    // Transfer(transaction, sender, receiver, amount)
                    tx.addTransfer(new Transfer(tx, td.sender, td.receiver, td.amount));
                }
            }
            // 4-3) 최종 save: Wallet → Transaction → Transfer 모두 cascade로 저장
            walletRepository.save(w);
        }

        System.out.println("지갑 및 연관 트랜잭션 초기화 완료");
    }
}
