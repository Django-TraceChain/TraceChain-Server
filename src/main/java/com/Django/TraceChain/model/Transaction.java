package com.Django.TraceChain.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @Column(name = "transaction_id", nullable = false)
    private String txID;

    @Column(precision = 36, scale = 18, nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @ManyToMany(mappedBy = "transactions", fetch = FetchType.LAZY)
    private List<Wallet> wallets = new ArrayList<>();

    @OneToMany(mappedBy = "transaction",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<Transfer> transfers = new ArrayList<>();

    public Transaction() {}

    public Transaction(String txID, BigDecimal amount, LocalDateTime timestamp) {
        this.txID = txID;
        this.amount = amount;
        this.timestamp = timestamp;
    }

    public void addTransfer(Transfer rel) {
        transfers.add(rel);
        rel.setTransaction(this);
    }

    public void removeTransfer(Transfer rel) {
        transfers.remove(rel);
        rel.setTransaction(null);
    }

    public String getTxID() { return txID; }
    public void setTxID(String txID) { this.txID = txID; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction)) return false;
        Transaction that = (Transaction) o;
        return txID != null && txID.equals(that.txID);
    }

    @Override
    public int hashCode() {
        return Objects.hash(txID);
    }
}
