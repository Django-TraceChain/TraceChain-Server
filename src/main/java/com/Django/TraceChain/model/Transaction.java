package com.Django.TraceChain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @Column(name = "tx_id", nullable = false)
    private String txID;             // 트랜잭션 ID (기본키, NOT NULL)

    @Column(nullable = false)
    private long amount;             // 금액 (예: satoshi 단위, NOT NULL)

    @Column(nullable = false)
    private LocalDateTime timestamp; // 트랜잭션 발생 시간 (NOT NULL)

    // 이 트랜잭션이 속한 지갑 (N:1)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_address", nullable = false)
    private Wallet wallet;

    // 입출금 관계 리스트 (1:N)
    @OneToMany(mappedBy = "transaction",
               cascade = CascadeType.ALL,
               orphanRemoval = true,
               fetch = FetchType.LAZY)
    private List<Transfer> transfers = new ArrayList<>();

    // 기본 생성자
    public Transaction() {}

    // 주요 필드만 초기화하는 생성자
    public Transaction(String txID, long amount, LocalDateTime timestamp) {
        this.txID      = txID;
        this.amount    = amount;
        this.timestamp = timestamp;
    }

    // Helper: 입출금 관계 추가
    public void addTransfer(Transfer rel) {
        transfers.add(rel);
        rel.setTransaction(this);
    }

    // Getter / Setter
    public String getTxID() { return txID; }
    public void setTxID(String txID) { this.txID = txID; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public Wallet getWallet() { return wallet; }
    public void setWallet(Wallet wallet) { this.wallet = wallet; }

    public List<Transfer> getTransfers() { return transfers; }
    public void setTransfers(List<Transfer> transfers) {
        this.transfers = transfers;
        for (Transfer rel : transfers) {
            rel.setTransaction(this);
        }
    }
}
