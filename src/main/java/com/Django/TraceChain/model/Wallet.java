package com.Django.TraceChain.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Transient;

import jakarta.persistence.*;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private int type; // 1 = Bitcoin, 2 = Ethereum

    @Column(precision = 36, scale = 18, nullable = false)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(columnDefinition = "TINYINT(1)", nullable = true)
    private Boolean fixedAmountPattern;

    @Column(columnDefinition = "TINYINT(1)", nullable = true)
    private Boolean multiIOPattern;

    @Column(columnDefinition = "TINYINT(1)", nullable = true)
    private Boolean loopingPattern;

    @Column(columnDefinition = "TINYINT(1)", nullable = true)
    private Boolean relayerPattern;

    @Column(columnDefinition = "TINYINT(1)", nullable = true)
    private Boolean peelChainPattern;

    @Column(nullable = true)
    private int patternCnt;

    @Transient
    private boolean newlyFetched = false;

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    @JoinTable(
            name = "wallet_transaction",
            joinColumns = @JoinColumn(name = "wallet_address"),
            inverseJoinColumns = @JoinColumn(name = "transaction_id")
    )
    private List<Transaction> transactions = new ArrayList<>();

    public Wallet() {}

    public Wallet(String address, int type, BigDecimal balance) {
        this.address = address;
        this.type = type;
        this.balance = balance;
    }

    public Wallet(String address,
                  int type,
                  BigDecimal balance,
                  Boolean fixedAmountPattern,
                  Boolean multiIOPattern,
                  Boolean loopingPattern,
                  Boolean relayerPattern,
                  Boolean peelChainPattern,
                  int patternCnt) {
        this.address = address;
        this.type = type;
        this.balance = balance;
        this.fixedAmountPattern = fixedAmountPattern;
        this.multiIOPattern = multiIOPattern;
        this.loopingPattern = loopingPattern;
        this.relayerPattern = relayerPattern;
        this.peelChainPattern = peelChainPattern;
        this.patternCnt = patternCnt;
    }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public int getType() { return type; }
    public void setType(int type) { this.type = type; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public Boolean getFixedAmountPattern() { return fixedAmountPattern; }
    public void setFixedAmountPattern(Boolean fixedAmountPattern) {
        this.fixedAmountPattern = fixedAmountPattern;
    }

    public Boolean getMultiIOPattern() { return multiIOPattern; }
    public void setMultiIOPattern(Boolean multiIOPattern) {
        this.multiIOPattern = multiIOPattern;
    }

    public Boolean getLoopingPattern() { return loopingPattern; }
    public void setLoopingPattern(Boolean loopingPattern) {
        this.loopingPattern = loopingPattern;
    }

    public Boolean getRelayerPattern() { return relayerPattern; }
    public void setRelayerPattern(Boolean relayerPattern) {
        this.relayerPattern = relayerPattern;
    }

    public Boolean getPeelChainPattern() { return peelChainPattern; }
    public void setPeelChainPattern(Boolean peelChainPattern) {
        this.peelChainPattern = peelChainPattern;
    }

    public int getPatternCnt() {
        return patternCnt;
    }

    public void setPatternCnt(int patternCnt) {
        this.patternCnt = patternCnt;
    }

    public List<Transaction> getTransactions() { return transactions; }
    public void setTransactions(List<Transaction> transactions) {
        this.transactions = transactions;
        for (Transaction tx : transactions) {
            if (!tx.getWallets().contains(this)) {
                tx.getWallets().add(this);
            }
        }
    }

    public boolean isNewlyFetched() {
        return newlyFetched;
    }

    public void setNewlyFetched(boolean newlyFetched) {
        this.newlyFetched = newlyFetched;
    }

    public void addTransaction(Transaction tx) {
        if (!transactions.contains(tx)) {
            transactions.add(tx);
            tx.getWallets().add(this);
        }
    }

    public void removeTransaction(Transaction tx) {
        if (transactions.remove(tx)) {
            tx.getWallets().remove(this);
        }
    }
    
    public void resetPatterns() {
        this.fixedAmountPattern = null;
        this.multiIOPattern = null;
        this.loopingPattern = null;
        this.relayerPattern = null;
        this.peelChainPattern = null;
        this.patternCnt = 0;
    }
}
