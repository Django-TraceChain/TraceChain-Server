package com.Django.TraceChain.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.WalletRepository;



@Service
public class MultiIODetector implements MixingDetector{

	@Autowired
	private WalletRepository walletRepository;
	
	@Override
	public void analyze(List<Wallet> wallets) {
	    for (Wallet wallet : wallets) {
	        List<Transaction> transactions = wallet.getTransactions();
	        String walletAddress = wallet.getAddress();

	        // Transaction들을 timestamp 기준으로 정렬
	        transactions.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));

	        boolean detected = false;

	        for (int i = 0; i < transactions.size(); i++) {
	            int sendCnt = 0;
	            int receiveCnt = 0;
	            LocalDateTime startTime = transactions.get(i).getTimestamp();

	            for (int j = i; j < transactions.size(); j++) {
	                Transaction currentTx = transactions.get(j);
	                LocalDateTime currentTime = currentTx.getTimestamp();
	                long seconds = Duration.between(startTime, currentTime).getSeconds();

	                if (seconds > 300) break; // 5분 초과

	                for (Transfer transfer : currentTx.getTransfers()) {
	                    if (walletAddress.equals(transfer.getSender())) sendCnt++;
	                    if (walletAddress.equals(transfer.getReceiver())) receiveCnt++;
	                }
	            }

	            if (sendCnt >= 3 && receiveCnt >= 3) {
	                detected = true;
	                break;
	            }
	        }

	        wallet.setMultiIOPattern(detected);
	        if (detected) {
	            wallet.setPatternCnt(wallet.getPatternCnt() + 1);
	        }
	        walletRepository.save(wallet);
	    }
	}

}
