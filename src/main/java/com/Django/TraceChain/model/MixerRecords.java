package com.Django.TraceChain.model;

import jakarta.persistence.*;
import java.util.Objects;

@Entity
@Table(name = "mixer_records")
public class MixerRecords {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "wallet_address", referencedColumnName = "address")
    private Wallet wallet; // 외래키: Wallet.address

    private int mixCount;     // 해당 지갑과 연관된 믹싱 횟수
    private String mixType;   // 믹싱 유형 (ex. CoinJoin, TornadoCash 등)

    public MixerRecords() {}

    public MixerRecords(Wallet wallet, int mixCount, String mixType) {
        this.wallet = wallet;
        this.mixCount = mixCount;
        this.mixType = mixType;
    }

    // Getter/Setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Wallet getWallet() { return wallet; }
    public void setWallet(Wallet wallet) { this.wallet = wallet; }

    public int getMixCount() { return mixCount; }
    public void setMixCount(int mixCount) { this.mixCount = mixCount; }

    public String getMixType() { return mixType; }
    public void setMixType(String mixType) { this.mixType = mixType; }
}
