package com.Django.TraceChain.dto;

import com.Django.TraceChain.model.Wallet;

import java.util.ArrayList;
import java.util.List;

public class PatternUtils {
    public static List<String> extractPatterns(Wallet wallet) {
        List<String> patterns = new ArrayList<>();
        if (Boolean.TRUE.equals(wallet.getFixedAmountPattern())) patterns.add("FixedAmount");
        if (Boolean.TRUE.equals(wallet.getMultiIOPattern())) patterns.add("MultiIO");
        if (Boolean.TRUE.equals(wallet.getLoopingPattern())) patterns.add("Looping");
        if (Boolean.TRUE.equals(wallet.getRelayerPattern())) patterns.add("Relayer");
        if (Boolean.TRUE.equals(wallet.getPeelChainPattern())) patterns.add("PeelChain");
        return patterns;
    }
}