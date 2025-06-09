package com.Django.TraceChain.dto;

import com.Django.TraceChain.model.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

public class DtoMapper {

    public static WalletDto mapWallet(Wallet wallet) {
        List<String> patterns = PatternUtils.extractPatterns(wallet);
        List<TransactionDto> txDtos = wallet.getTransactions().stream()
                .map(DtoMapper::mapTransaction)
                .collect(Collectors.toList());

        // balance가 BigDecimal이므로 그대로 전달
        BigDecimal balance = wallet.getBalance();
        return new WalletDto(wallet.getAddress(), balance, txDtos, patterns);
    }

    public static TransactionDto mapTransaction(Transaction tx) {
        List<TransferDto> tDtos = tx.getTransfers().stream()
                .map(t -> new TransferDto(t.getSender(), t.getReceiver(), t.getAmount()))
                .collect(Collectors.toList());

        // tx.getAmount()가 이미 BTC 단위이면 변환하지 말 것
        BigDecimal amountInBTC = tx.getAmount(); // divide 제거

        return new TransactionDto(tx.getTxID(), amountInBTC, tx.getTimestamp(), tDtos);
    }

}
