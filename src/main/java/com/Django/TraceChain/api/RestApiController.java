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
        if (chain.equals("ethereum")) {
            Set<String> visited = new HashSet<>();
            Map<Integer, List<Wallet>> depthMap = new TreeMap<>();
            ChainClient client = walletService.resolveClient("ethereum");
            if (client instanceof EthereumClient ethClient) {
                ethClient.traceRecursiveDetailed(address, 0, 0, depthMap, visited);
            }
        }
        Wallet wallet = walletService.findAddress(chain, address);
        wallet.setTransactions(walletService.getTransactions(chain, address));
        return ResponseEntity.ok(DtoMapper.mapWallet(wallet));
    }

    @GetMapping("/search-limited")
    public ResponseEntity<WalletDto> searchLimited(@RequestParam String address,
                                                   @RequestParam(defaultValue = "bitcoin") String chain,
                                                   @RequestParam(defaultValue = "10") int limit) {
        if (chain.equals("ethereum")) {
            Set<String> visited = new HashSet<>();
            Map<Integer, List<Wallet>> depthMap = new TreeMap<>();
            ChainClient client = walletService.resolveClient("ethereum");
            if (client instanceof EthereumClient ethClient) {
                ethClient.traceRecursiveDetailed(address, 0, 0, depthMap, visited);
            }
        }
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
        if (chain.equals("ethereum")) {
            ChainClient client = walletService.resolveClient("ethereum");
            if (client instanceof EthereumClient ethClient) {
                ethClient.traceRecursiveDetailed(address, depth, maxDepth, new TreeMap<>(), visited);
            }
        } else {
            walletService.traceTransactionsRecursive(chain, address, depth, maxDepth, visited);
        }
        return ResponseEntity.ok(visited);
    }

    @GetMapping("/trace-detailed")
    public ResponseEntity<Map<Integer, List<WalletDto>>> traceDetailed(@RequestParam String address,
                                                                       @RequestParam(defaultValue = "bitcoin") String chain,
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
    public ResponseEntity<List<WalletDto>> detectAllPatterns() {
        // 1. 모든 지갑을 가져옴
        List<Wallet> wallets = walletService.getAllWallets();


        // 2. 모든 탐지기 실행
        detectService.runAllDetectors(wallets);

        // 3. 결과 매핑 및 반환
        List<WalletDto> results = wallets.stream()
                                         .map(DtoMapper::mapWallet)
                                         .collect(Collectors.toList());

        return ResponseEntity.ok(results);
    }


    @GetMapping("/detect-looping")
    public ResponseEntity<List<WalletDto>> detectLoopingOnly(@RequestParam String address,
                                                             @RequestParam(defaultValue = "bitcoin") String chain) {
        if (chain.equals("ethereum")) {
            Set<String> visited = new HashSet<>();
            Map<Integer, List<Wallet>> depthMap = new TreeMap<>();
            ChainClient client = walletService.resolveClient("ethereum");
            if (client instanceof EthereumClient ethClient) {
                ethClient.traceRecursiveDetailed(address, 0, 0, depthMap, visited);
            }
        }

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
