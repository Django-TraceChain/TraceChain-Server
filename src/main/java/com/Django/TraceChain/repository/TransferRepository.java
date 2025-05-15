package com.Django.TraceChain.repository;

import com.Django.TraceChain.model.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, Long> {

    // 특정 트랜잭션 ID로 조회
    List<Transfer> findByTransaction_TxID(String txID);

    // 특정 송신자 주소로 조회
    List<Transfer> findBySender(String sender);

    // 특정 수신자 주소로 조회
    List<Transfer> findByReceiver(String receiver);
    
    List<Transfer> findAll();
}
