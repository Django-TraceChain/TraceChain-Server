package com.Django.TraceChain.dto;

import com.Django.TraceChain.model.*;

import java.util.List;
import java.util.stream.Collectors;

public class DtoMapper {
    public static WalletDto mapWallet(Wallet wallet) {
        List<String> patterns = PatternUtils.extractPatterns(wallet);
        List<TransactionDto> txDtos = wallet.getTransactions().stream()
                .map(DtoMapper::mapTransaction)
                .collect(Collectors.toList());
        return new WalletDto(wallet.getAddress(), wallet.getBalance(), txDtos, patterns);
    }

    public static TransactionDto mapTransaction(Transaction tx) {
        List<TransferDto> tDtos = tx.getTransfers().stream()
                .map(t -> new TransferDto(t.getSender(), t.getReceiver(), t.getAmount()))
                .collect(Collectors.toList());
        return new TransactionDto(tx.getTxID(), tx.getAmount(), tx.getTimestamp(), tDtos);
    }
}