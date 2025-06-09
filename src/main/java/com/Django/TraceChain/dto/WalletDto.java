package com.Django.TraceChain.dto;

import java.math.BigDecimal;
import java.util.List;

public class WalletDto {
    private String address;
    private String balance; // 변경: BigDecimal → String
    private List<TransactionDto> transactions;
    private List<String> patterns;

    public WalletDto(String address, BigDecimal balance, List<TransactionDto> transactions, List<String> patterns) {
        this.address = address;
        this.balance = balance.toPlainString(); // 핵심
        this.transactions = transactions;
        this.patterns = patterns;
    }

    public String getAddress() { return address; }
    public String getBalance() { return balance; } // 반환도 String
    public List<TransactionDto> getTransactions() { return transactions; }
    public List<String> getPatterns() { return patterns; }
}
