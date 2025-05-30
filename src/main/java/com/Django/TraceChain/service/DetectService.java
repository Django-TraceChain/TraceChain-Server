package com.Django.TraceChain.service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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

		int type = wallets.get(0).getType();

		// 🔍 pattern_cnt가 null인 지갑만 탐지 대상으로 설정 (Objects.isNull 사용)
		List<Wallet> filtered = wallets.stream()
				.filter(wallet -> Objects.isNull(wallet.getPatternCnt()))
				.collect(Collectors.toList());

		if (filtered.isEmpty()) return;

		for (MixingDetector detector : detectors) {
			if (type == 2 && detector instanceof PeelChainDetector) continue;
			if (type == 1 && detector instanceof RelayerDetector) continue;

			detector.analyze(filtered);
		}
	}
}
