package com.Django.TraceChain.service;

import java.util.List;

import com.Django.TraceChain.model.Wallet;

public interface MixingDetector {
	void analyze(List<Wallet> wallets);
}
