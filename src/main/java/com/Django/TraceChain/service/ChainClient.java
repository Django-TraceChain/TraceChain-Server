/*
 * BitcoinClient와 EthereumClient의 인터페이스
 */
package com.Django.TraceChain.service;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Wallet;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface ChainClient {
	Wallet findAddress(String address);
	List<Transaction> getTransactions(String address);
	List<Transaction> getTransactions(String address, int limit); // 오버로딩

	void traceAllTransactionsRecursive(String address, int depth, int maxDepth, Set<String> visited);
	void traceLimitedTransactionsRecursive(String address, int depth, int maxDepth,
			Map<Integer, List<Wallet>> depthMap, Set<String> visited);
	boolean supports(String chainType); // 예: "bitcoin", "ethereum" 구분용
}
