package com.Django.TraceChain.service;

import com.Django.TraceChain.model.Wallet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DetectService {

    private final List<MixingDetector> detectors;

    @Autowired
    public DetectService(List<MixingDetector> detectors) {
        this.detectors = detectors;
    }

    public void runAllDetectors(List<Wallet> wallets) {
        if (wallets == null || wallets.isEmpty()) return;

        // 체인별 분리
        List<Wallet> bitcoinWallets = wallets.stream()
                .filter(w -> w.getType() == 1)
                .toList();

        List<Wallet> ethereumWallets = wallets.stream()
                .filter(w -> w.getType() == 2)
                .toList();

        // 비트코인용: Relayer/EthereumLooping 제외
        if (!bitcoinWallets.isEmpty()) {
            for (MixingDetector detector : detectors) {
                if (detector instanceof RelayerDetector) continue;
                if (detector instanceof EthereumLoopingDetector) continue;
                detector.analyze(bitcoinWallets);
            }
        }

        // 이더리움용: PeelChain/Looping 제외
        if (!ethereumWallets.isEmpty()) {
            for (MixingDetector detector : detectors) {
                if (detector instanceof PeelChainDetector) continue;
                if (detector instanceof LoopingDetector) continue;
                detector.analyze(ethereumWallets);
            }
        }
    }
}
