package com.Django.TraceChain.model;

import jakarta.persistence.*;

@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                // 자동 생성 PK

    // 이 관계가 속한 트랜잭션 (N:1)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", referencedColumnName = "transaction_id", nullable = false)
    private Transaction transaction;

    @Column(nullable = false)
    private String sender;          // 보낸 지갑 주소 (NOT NULL)

    @Column(nullable = false)
    private String receiver;        // 받은 지갑 주소 (NOT NULL)

    @Column(nullable = false)
    private long amount;            // 해당 입출력의 금액 (NOT NULL)

    // 기본 생성자
    public Transfer() {}

    // 전체 필드 초기화 생성자
    public Transfer(Transaction transaction,
                    String sender,
                    String receiver,
                    long amount) {
        this.transaction = transaction;
        this.sender = sender;
        this.receiver = receiver;
        this.amount = amount;
    }

    // Getter / Setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Transaction getTransaction() { return transaction; }
    public void setTransaction(Transaction transaction) { this.transaction = transaction; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
}
