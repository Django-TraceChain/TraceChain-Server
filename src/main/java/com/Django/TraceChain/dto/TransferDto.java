package com.Django.TraceChain.dto;

public class TransferDto {
    private String sender;
    private String receiver;
    private long amount;

    public TransferDto(String sender, String receiver, long amount) {
        this.sender = sender;
        this.receiver = receiver;
        this.amount = amount;
    }

    public String getSender() { return sender; }
    public String getReceiver() { return receiver; }
    public long getAmount() { return amount; }
}