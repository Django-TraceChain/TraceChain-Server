package com.Django.TraceChain.Repository;

import com.Django.TraceChain.model.WalletRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WalletRelationRepository extends JpaRepository<WalletRelation, Long> {

    // 특정 트랜잭션 ID로 조회
    List<WalletRelation> findByTransaction_TxID(String txID);

    // 특정 송신자 주소로 조회
    List<WalletRelation> findBySender(String sender);

    // 특정 수신자 주소로 조회
    List<WalletRelation> findByReceiver(String receiver);
    
    List<WalletRelation> findAll();
}
