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
        for (MixingDetector detector : detectors) {
            detector.analyze(wallets);
        }
    }
}
