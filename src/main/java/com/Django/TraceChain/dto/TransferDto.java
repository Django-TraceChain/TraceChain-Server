package com.Django.TraceChain.dto;

import java.math.BigDecimal;

public class TransferDto {
    private String sender;
    private String receiver;
    private BigDecimal amount;

    public TransferDto(String sender, String receiver, BigDecimal amount) {
        this.sender = sender;
        this.receiver = receiver;
        this.amount = amount;
    }

    public String getSender() { return sender; }
    public String getReceiver() { return receiver; }
    public BigDecimal getAmount() { return amount; }

    public void setSender(String sender) { this.sender = sender; }
    public void setReceiver(String receiver) { this.receiver = receiver; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
