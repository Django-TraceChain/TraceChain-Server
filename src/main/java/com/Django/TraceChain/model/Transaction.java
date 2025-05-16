package com.Django.TraceChain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @Column(name = "transaction_id", nullable = false)
    private String txID;             // 트랜잭션 ID (기본키, NOT NULL)

    @Column(nullable = false)
    private long amount;             // 금액 (예: satoshi 단위, NOT NULL)

    @Column(nullable = false)
    private LocalDateTime timestamp; // 트랜잭션 발생 시간 (NOT NULL)

    // Wallet과 다대다 양방향 관계 (mappedBy Wallet.transactions)
    @ManyToMany(mappedBy = "transactions", fetch = FetchType.LAZY)
    private List<Wallet> wallets = new ArrayList<>();

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
        this.txID = txID;
        this.amount = amount;
        this.timestamp = timestamp;
    }

    // Helper: 입출금 관계 추가
    public void addTransfer(Transfer rel) {
        transfers.add(rel);
        rel.setTransaction(this);
    }

    public void removeTransfer(Transfer rel) {
        transfers.remove(rel);
        rel.setTransaction(null);
    }

    // Getter / Setter
    public String getTxID() { return txID; }
    public void setTxID(String txID) { this.txID = txID; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public List<Wallet> getWallets() { return wallets; }
    public void setWallets(List<Wallet> wallets) {
        this.wallets = wallets;
        for (Wallet wallet : wallets) {
            if (!wallet.getTransactions().contains(this)) {
                wallet.getTransactions().add(this);
            }
        }
    }

    public List<Transfer> getTransfers() { return transfers; }
    public void setTransfers(List<Transfer> transfers) {
        this.transfers = transfers;
        for (Transfer rel : transfers) {
            rel.setTransaction(this);
        }
    }
}
