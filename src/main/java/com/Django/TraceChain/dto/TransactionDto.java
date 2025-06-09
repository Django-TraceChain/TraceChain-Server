package com.Django.TraceChain.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class TransactionDto {
    private String txID;
    private String amount;  // BigDecimal → String
    private LocalDateTime timestamp;
    private List<TransferDto> transfers;

    public TransactionDto(String txID, BigDecimal amount, LocalDateTime timestamp, List<TransferDto> transfers) {
        this.txID = txID;
        this.amount = amount.toPlainString(); // 핵심: 과학적 표기법 방지
        this.timestamp = timestamp;
        this.transfers = transfers;
    }

    public String getTxID() {
        return txID;
    }

    public String getAmount() {
        return amount;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public List<TransferDto> getTransfers() {
        return transfers;
    }
}
