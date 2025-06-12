package com.Django.TraceChain.repository;

import com.Django.TraceChain.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    // 주소에 연결된 모든 트랜잭션 반환 (지연 로딩)
    @Query("SELECT t FROM Transaction t JOIN t.wallets w WHERE w.address = :address")
    List<Transaction> findAllByWalletAddress(@Param("address") String address);

    // 특정 txID에 대해 transfers까지 fetch join
    @Query("SELECT DISTINCT t FROM Transaction t LEFT JOIN FETCH t.transfers WHERE t.txID = :txID")
    Optional<Transaction> findByIdWithTransfers(@Param("txID") String txID);

    // 여러 txID에 대해 transfers까지 fetch join
    @Query("SELECT DISTINCT t FROM Transaction t LEFT JOIN FETCH t.transfers WHERE t.txID IN :txIDs")
    List<Transaction> findAllByIdWithTransfers(@Param("txIDs") List<String> txIDs);

    // 주소로 연결된 트랜잭션들을 최신순으로 페이징
    @Query("SELECT DISTINCT t FROM Transaction t JOIN t.wallets w WHERE w.address = :address ORDER BY t.timestamp DESC")
    Page<Transaction> findByWalletAddress(@Param("address") String address, Pageable pageable);
}
