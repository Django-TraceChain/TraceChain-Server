package com.Django.TraceChain.repository;

import com.Django.TraceChain.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, String> {
    // 필요 시 커스텀 메서드 정의 가능
}
