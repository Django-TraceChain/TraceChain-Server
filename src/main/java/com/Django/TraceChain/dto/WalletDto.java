package com.Django.TraceChain.dto;

import java.math.BigDecimal;
import java.util.List;

public class WalletDto {
    private String address;
    private BigDecimal balance;
    private List<TransactionDto> transactions;
    private List<String> patterns;

    public WalletDto(String address, BigDecimal balance, List<TransactionDto> transactions, List<String> patterns) {
        this.address = address;
        this.balance = balance;
        this.transactions = transactions;
        this.patterns = patterns;
    }

    public String getAddress() { return address; }
    public BigDecimal getBalance() { return balance; }
    public List<TransactionDto> getTransactions() { return transactions; }
    public List<String> getPatterns() { return patterns; }
}