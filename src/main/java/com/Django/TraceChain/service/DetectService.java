package com.Django.TraceChain.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.Django.TraceChain.model.Wallet;

@Service
public class DetectService {
	
	private final List<MixingDetector> detectors;

	@Autowired
    public DetectService(List<MixingDetector> detectors) {
        this.detectors = detectors;
    }

	public void runAllDetectors(List<Wallet> wallets) {
	    if (wallets.isEmpty()) return;

	    // fixedAmountPattern이 null인 지갑만 필터링
	    List<Wallet> filteredWallets = wallets.stream()
	        .filter(wallet -> wallet.getFixedAmountPattern() == null)
	        .toList();

	    if (filteredWallets.isEmpty()) return;

	    // 지갑을 타입별로 분리
	    List<Wallet> bitcoinWallets = filteredWallets.stream()
	        .filter(wallet -> wallet.getType() == 1)
	        .toList();

	    List<Wallet> ethereumWallets = filteredWallets.stream()
	        .filter(wallet -> wallet.getType() == 2)
	        .toList();

	    // 비트코인 지갑 분석 (RelayerDetector 제외)
	    if (!bitcoinWallets.isEmpty()) {
	        for (MixingDetector detector : detectors) {
	            if (detector instanceof RelayerDetector) continue;
	            detector.analyze(bitcoinWallets);
	        }
	    }

	    // 이더리움 지갑 분석 (PeelChainDetector 제외)
	    if (!ethereumWallets.isEmpty()) {
	        for (MixingDetector detector : detectors) {
	            if (detector instanceof PeelChainDetector) continue;
	            detector.analyze(ethereumWallets);
	        }
	    }
	}


}
