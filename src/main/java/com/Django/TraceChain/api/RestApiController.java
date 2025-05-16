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
    public ResponseEntity<String> detectPatterns() {
        List<Wallet> wallets = walletService.getAllWallets();
        detectService.runAllDetectors(wallets);
        return ResponseEntity.ok("Mixing pattern detection completed.");
    }
}
