package com.Django.TraceChain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "wallets")
public class Wallet {

	@Id
	private String address;      // 지갑 주소 (기본키)

	private int type;			// 지갑 유형 (비트코인 or 이더리움)
	private long balance;        // 보유 금액 (satoshi 등)

	// 생성자
	public Wallet() {}

	public Wallet(String address, int type, long balance) {
		this.address = address;
		this.type = type;
		this.balance = balance;
	}

	// getter/setter
	public String getAddress() { return address; }
	public void setAddress(String address) { this.address = address; }

	public int getType() { return type; }
	public void setType(int type) { this.type = type; }

	public long getBalance() { return balance; }
	public void setBalance(long balance) { this.balance = balance; }
}

