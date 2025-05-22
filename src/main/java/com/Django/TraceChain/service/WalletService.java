package com.Django.TraceChain.service;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.WalletRepository;

import org.springframework.stereotype.Service;

/*
 * 사용자가 주소를 검색하면 Controller로부터 전닯받은 뒤, BitcoinClient 또는 EthereumClient로 전달
 */
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WalletService {

    private final AccessToken accessToken;
    private final List<ChainClient> chainClients;
    private final WalletRepository walletRepository;

    public WalletService(AccessToken accessToken,
                         List<ChainClient> chainClients,
                         WalletRepository walletRepository) {
        this.accessToken = accessToken;
        this.chainClients = chainClients;
        this.walletRepository = walletRepository;
    }

    private ChainClient getClient(String chainType) {
        return chainClients.stream()
                .filter(client -> client.supports(chainType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported chain type: " + chainType));
    }

    public Wallet findAddress(String chainType, String address) {
        return getClient(chainType).findAddress(address);
    }
    
    public List<Transaction> getTransactions(String chainType, String address) {
        return getClient(chainType).getTransactions(address);
    }

    public List<Transaction> getTransactions(String chainType, String address, int limit) {
        return getClient(chainType).getTransactions(address, limit);
    } // 몇개를 가져올지 한계를 정해서 트랜잭션 가져오면 단계로 재귀를 돌려도 할만하지 않을까?

    
    public void traceAllTransactionsRecursive(String chainType, String address, int depth, int maxDepth, Set<String> visited) {
    	getClient(chainType).traceAllTransactionsRecursive(address, depth, maxDepth, visited);
    }
    
    public void traceLimitedTransactionsRecursive(String chainType, String address, int depth, int maxDepth,
            Map<Integer, List<Wallet>> depthMap, Set<String> visited) {
    	getClient(chainType).traceLimitedTransactionsRecursive(address, depth, maxDepth, depthMap, visited);
    }
    
    public List<Wallet> getAllWallets() {
        return walletRepository.findAll();
    }


    public ChainClient resolveClient(String chainType) {
        return getClient(chainType);
    }

}
