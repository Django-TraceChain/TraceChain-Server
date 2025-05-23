package com.Django.TraceChain.repository;

import com.Django.TraceChain.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    // 기존 쿼리 유지
    @Query("SELECT t FROM Transaction t JOIN t.wallets w WHERE w.address = :address")
    List<Transaction> findAllByWalletAddress(@Param("address") String address);

    // 1. transfers 컬렉션도 같이 조회하는 메서드 (fetch join)
    @Query("SELECT DISTINCT t FROM Transaction t LEFT JOIN FETCH t.transfers WHERE t.txID = :txID")
    Optional<Transaction> findByIdWithTransfers(@Param("txID") String txID);

    // 2. 여러 개 트랜잭션에 대해 transfers도 한꺼번에 조회 (필요 시)
    @Query("SELECT DISTINCT t FROM Transaction t LEFT JOIN FETCH t.transfers WHERE t.txID IN :txIDs")
    List<Transaction> findAllByIdWithTransfers(@Param("txIDs") List<String> txIDs);

}