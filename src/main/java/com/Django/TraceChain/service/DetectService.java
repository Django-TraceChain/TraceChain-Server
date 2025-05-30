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

		for (MixingDetector detector : detectors) {

			// PeelChainDetector는 type==2에서 제외
			if (type == 2 && detector instanceof PeelChainDetector) continue;

			// RelayerDetector는 type==1에서 제외
			if (type == 1 && detector instanceof RelayerDetector) continue;

			// FixedAmountDetector만 fixedAmountPattern == null 필터링
			if (detector instanceof FixedAmountDetector) {
				List<Wallet> filtered = wallets.stream()
						.filter(wallet -> wallet.getFixedAmountPattern() == null)
						.collect(Collectors.toList());

				if (!filtered.isEmpty()) {
					detector.analyze(filtered);
				}
			} else {
				// 다른 탐지기는 전체 대상 (원래 로직 유지)
				detector.analyze(wallets);
			}
		}
	}
}
