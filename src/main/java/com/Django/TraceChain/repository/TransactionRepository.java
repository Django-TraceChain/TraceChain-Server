package com.Django.TraceChain.repository;

import com.Django.TraceChain.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    @Query("SELECT t FROM Transaction t JOIN t.wallets w WHERE w.address = :address")
    List<Transaction> findAllByWalletAddress(@Param("address") String address);

}
