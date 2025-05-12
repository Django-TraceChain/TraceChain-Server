package com.Django.TraceChain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    private String txID;             // 트랜잭션 ID (기본키)

    private long amount;             // 금액 (예: satoshi 단위)
    private LocalDateTime timestamp; // 타임스탬프 (트랜잭션 발생 시간)

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<WalletRelation> transfers; // 입출금 정보

    // 기본 생성자
    public Transaction() {}

    // 생성자
    public Transaction(String txID, long amount, LocalDateTime timestamp) {
        this.txID = txID;
        this.amount = amount;
        this.timestamp = timestamp;
    }

    // Getter/Setter
    public String getTxID() {
        return txID;
    }

    public void setTxID(String txID) {
        this.txID = txID;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public List<WalletRelation> getTransfers() {
        return transfers;
    }

    public void setTransfers(List<WalletRelation> transfers) {
        this.transfers = transfers;
    }
}
