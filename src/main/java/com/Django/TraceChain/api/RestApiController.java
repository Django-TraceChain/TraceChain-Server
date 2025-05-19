// JSON 기반 API 컨트롤러: RestApiController.java
// JSON 반환을 위한 /api/search, /api/search-limited, /api/trace, /api/trace-detailed,
// api/graph /api/detect
/*
 * New RESTful JSON API: /api/search
 */

package com.Django.TraceChain.api;

import com.Django.TraceChain.dto.*;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class RestApiController {

    private final WalletService walletService;
    private final DetectService detectService;

    @Autowired
    public RestApiController(WalletService walletService, DetectService detectService) {
        this.walletService = walletService;
        this.detectService = detectService;
    }

    @GetMapping("/search")
    public ResponseEntity<WalletDto> search(@RequestParam String address,
                                            @RequestParam(defaultValue = "bitcoin") String chain) {
        Wallet wallet = walletService.findAddress(chain, address);
        wallet.setTransactions(walletService.getTransactions(chain, address));
        return ResponseEntity.ok(DtoMapper.mapWallet(wallet));
    }

    @GetMapping("/search-limited")
    public ResponseEntity<WalletDto> searchLimited(@RequestParam String address,
                                                   @RequestParam(defaultValue = "bitcoin") String chain,
                                                   @RequestParam(defaultValue = "10") int limit) {
        Wallet wallet = walletService.findAddress(chain, address);
        wallet.setTransactions(walletService.getTransactions(chain, address, limit));
        return ResponseEntity.ok(DtoMapper.mapWallet(wallet));
    }

    @GetMapping("/trace")
    public ResponseEntity<Set<String>> trace(@RequestParam String address,
                                             @RequestParam(defaultValue = "bitcoin") String chain,
                                             @RequestParam(defaultValue = "0") int depth,
                                             @RequestParam(defaultValue = "2") int maxDepth) {
        Set<String> visited = new HashSet<>();
        walletService.traceTransactionsRecursive(chain, address, depth, maxDepth, visited);
        return ResponseEntity.ok(visited);
    }

    @GetMapping("/trace-detailed")
    public ResponseEntity<Map<Integer, List<WalletDto>>> traceDetailed(@RequestParam String address,
                                                                       @RequestParam String chain,
                                                                       @RequestParam(defaultValue = "0") int depth,
                                                                       @RequestParam(defaultValue = "2") int maxDepth) {
        Set<String> visited = new HashSet<>();
        Map<Integer, List<Wallet>> depthMap = new TreeMap<>();

        ChainClient client = walletService.resolveClient(chain);
        if (client instanceof EthereumClient ethClient) {
            ethClient.traceRecursiveDetailed(address, depth, maxDepth, depthMap, visited);
        } else if (client instanceof BitcoinClient btcClient) {
            btcClient.traceRecursiveDetailed(address, depth, maxDepth, depthMap, visited);
        } else {
            return ResponseEntity.badRequest().build();
        }

        Map<Integer, List<WalletDto>> dtoMap = depthMap.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream().map(DtoMapper::mapWallet).collect(Collectors.toList())
                ));

        return ResponseEntity.ok(dtoMap);
    }

    @GetMapping("/graph")
    public ResponseEntity<List<WalletDto>> graph() {
        List<Wallet> wallets = walletService.getAllWallets();
        return ResponseEntity.ok(wallets.stream().map(DtoMapper::mapWallet).collect(Collectors.toList()));
    }

    @GetMapping("/detect")
    public ResponseEntity<List<WalletDto>> detectPatterns(@RequestParam(required = false) String address,
                                                          @RequestParam(defaultValue = "bitcoin") String chain) {
        List<Wallet> wallets;

        if (address == null || address.isEmpty()) {
            // 전체 DB에 있는 지갑 대상으로 패턴 감지 실행
            wallets = walletService.getAllWallets();
            detectService.runAllDetectors(wallets);
        } else {
            // 입력된 주소에 대해 감지 실행
            Wallet wallet = walletService.findAddress(chain, address);
            if (wallet == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(List.of());
            }

            wallet.setTransactions(walletService.getTransactions(chain, address, 10));

            // 이미 모든 패턴이 감지된 지갑인지 확인
            boolean alreadyDetected =
                    wallet.getFixedAmountPattern() != null &&
                            wallet.getMultiIOPattern() != null &&
                            wallet.getLoopingPattern() != null &&
                            wallet.getRelayerPattern() != null &&
                            wallet.getPeelChainPattern() != null;

            List<Wallet> targetWallets = new ArrayList<>();
            targetWallets.add(wallet);

            if (!alreadyDetected) {
                // 연결된 주소들 재귀적으로 확장 (depth 2)
                Set<String> visited = new HashSet<>();
                walletService.traceTransactionsRecursive(chain, address, 0, 2, visited);

                for (String addr : visited) {
                    if (!addr.equals(address)) {
                        Wallet w = walletService.findAddress(chain, addr);
                        if (w == null) {
                            System.out.println("⚠️ 지갑 조회 실패: " + addr);
                            continue; // null 방어
                        }
                        w.setTransactions(walletService.getTransactions(chain, addr, 10));
                        targetWallets.add(w);
                    }
                }

                // 하나라도 패턴이 null인 지갑이 있다면 전체 탐지기 실행
                boolean shouldDetect = targetWallets.stream().anyMatch(w ->
                        w.getFixedAmountPattern() == null ||
                                w.getMultiIOPattern() == null ||
                                w.getLoopingPattern() == null ||
                                w.getRelayerPattern() == null ||
                                w.getPeelChainPattern() == null
                );

                if (shouldDetect) {
                    detectService.runAllDetectors(targetWallets);
                }
            }

            wallets = targetWallets;
        }

        // 결과를 DTO로 반환
        List<WalletDto> results = wallets.stream()
                .map(DtoMapper::mapWallet)
                .collect(Collectors.toList());

        return ResponseEntity.ok(results);
    }


    @GetMapping("/detect-looping")
    public ResponseEntity<List<WalletDto>> detectLoopingOnly(@RequestParam String address,
                                                             @RequestParam(defaultValue = "bitcoin") String chain) {
        Wallet wallet = walletService.findAddress(chain, address);
        wallet.setTransactions(walletService.getTransactions(chain, address, 10));

        Set<String> visited = new HashSet<>();
        walletService.traceTransactionsRecursive(chain, address, 0, 2, visited);

        List<Wallet> wallets = new ArrayList<>();
        wallets.add(wallet);

        for (String addr : visited) {
            if (!addr.equals(address)) {
                Wallet w = walletService.findAddress(chain, addr);
                if (w != null) {
                    w.setTransactions(walletService.getTransactions(chain, addr, 10));
                    wallets.add(w);
                }
            }
        }

        detectService.runLoopingOnly(wallets);

        List<WalletDto> result = wallets.stream().map(DtoMapper::mapWallet).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }




}
