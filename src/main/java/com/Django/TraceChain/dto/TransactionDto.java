package com.Django.TraceChain.dto;

import java.time.LocalDateTime;
import java.util.List;

public class TransactionDto {
    private String txID;
    private long amount;
    private LocalDateTime timestamp;
    private List<TransferDto> transfers;

    public TransactionDto(String txID, long amount, LocalDateTime timestamp, List<TransferDto> transfers) {
        this.txID = txID;
        this.amount = amount;
        this.timestamp = timestamp;
        this.transfers = transfers;
    }

    public String getTxID() { return txID; }
    public long getAmount() { return amount; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public List<TransferDto> getTransfers() { return transfers; }
}
