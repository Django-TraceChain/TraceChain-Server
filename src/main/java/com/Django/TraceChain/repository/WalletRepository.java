package com.Django.TraceChain.repository;

import com.Django.TraceChain.model.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, String> {
    // 필요 시 커스텀 메서드 정의 가능
    // 지갑과 해당 지갑의 트랜잭션을 한 번에 가져오는 쿼리
    @Query("SELECT w FROM Wallet w LEFT JOIN FETCH w.transactions WHERE w.address = :address")
    Optional<Wallet> findWithTransactionsByAddress(@Param("address") String address);
}