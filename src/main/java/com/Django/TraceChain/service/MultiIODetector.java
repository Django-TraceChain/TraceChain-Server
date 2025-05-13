package com.Django.TraceChain.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;

@Service
public class MultiIODetector implements MixingDetector{

	@Override
	public void analyze(List<Wallet> wallets) {
		for (Wallet wallet : wallets) {
            int sendCnt = 0;
            int receiveCnt = 0;

            List<Transaction> transactions = wallet.getTransactions();
            String walletAddress = wallet.getAddress();

            for (Transaction tx : transactions) {
                List<Transfer> transfers = tx.getTransfers();

                for (Transfer transfer : transfers) {
                    if (walletAddress.equals(transfer.getSender())) {
                        sendCnt++;
                    }
                    if (walletAddress.equals(transfer.getReceiver())) {
                        receiveCnt++;
                    }
                }
            }

            System.out.println(sendCnt + " " + receiveCnt);

            // IO Multi Structure Pattern 탐지 기준
            if (sendCnt >= 3 && receiveCnt >= 3) {
            	System.out.println("Detect MultiIO");
                wallet.setMultiIOPattern(true);
                wallet.setPatternCnt(wallet.getPatternCnt() + 1);
            } else {
                wallet.setMultiIOPattern(false);
            }
            
        }
		System.out.println("Finish MultiIO");
	}

}
