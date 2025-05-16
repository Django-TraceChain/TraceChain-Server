package com.Django.TraceChain.dto;

import java.util.List;

public class WalletDto {
    private String address;
    private long balance;
    private List<TransactionDto> transactions;
    private List<String> patterns;

    public WalletDto(String address, long balance, List<TransactionDto> transactions, List<String> patterns) {
        this.address = address;
        this.balance = balance;
        this.transactions = transactions;
        this.patterns = patterns;
    }

    public String getAddress() { return address; }
    public long getBalance() { return balance; }
    public List<TransactionDto> getTransactions() { return transactions; }
    public List<String> getPatterns() { return patterns; }
}