package com.Django.TraceChain.component;

import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.repository.WalletRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DatabaseInitializer implements CommandLineRunner {

    private final WalletRepository walletRepository;

    public DatabaseInitializer(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        List<Wallet> wallets = walletRepository.findAll();
        for (Wallet wallet : wallets) {
            wallet.resetPatterns(); // 패턴 필드 초기화
        }
        walletRepository.saveAll(wallets); // DB에 저장
        System.out.println("All wallet patterns reset.");
    }
}
