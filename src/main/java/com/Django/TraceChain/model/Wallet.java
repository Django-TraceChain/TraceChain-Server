package com.Django.TraceChain.model;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @Column(nullable = false)
    private String address;      // 지갑 주소 (기본키, NOT NULL)

    @Column(nullable = false)
    private int type;            // 지갑 유형 (비트코인 or 이더리움, NOT NULL)

    @Column(nullable = false)
    private long balance;        // 보유 금액 (satoshi 등, NOT NULL)

    // 믹싱 패턴 탐지 결과 (nullable로 설정)
    @Column(nullable = true)
    private Boolean fixedAmountPattern;

    @Column(nullable = true)
    private Boolean multiIOPattern;

    @Column(nullable = true)
    private Boolean loopingPattern;

    @Column(nullable = true)
    private Boolean relayerPattern;

    @Column(nullable = true)
    private Boolean peelChainPattern;

    @Column(nullable = true)
    private int patternCnt;
    
 // 연관된 트랜잭션들 (1:N)
    @OneToMany(mappedBy = "wallet",
               cascade = CascadeType.ALL,
               orphanRemoval = true,
               fetch = FetchType.LAZY)
    private List<Transaction> transactions = new ArrayList<>();
    
    public void addTransaction(Transaction tx) {
        transactions.add(tx);
        tx.setWallet(this);
    }
    
    // 생성자
    public Wallet() {}
    
    public Wallet(String address, int type, long balance) {
        this.address = address;
        this.type    = type;
        this.balance = balance;
    }

    public Wallet(String address,
                  int type,
                  long balance,
                  Boolean fixedAmountPattern,
                  Boolean multiIOPattern,
                  Boolean loopingPattern,
                  Boolean relayerPattern,
                  Boolean peelChainPattern, int patternCnt) {
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

    

	// Getter / Setter
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public int getType() { return type; }
    public void setType(int type) { this.type = type; }

    public long getBalance() { return balance; }
    public void setBalance(long balance) { this.balance = balance; }

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
            tx.setWallet(this);
        }
    }
}
