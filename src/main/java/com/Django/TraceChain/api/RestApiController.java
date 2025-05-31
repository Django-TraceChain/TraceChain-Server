package com.Django.TraceChain.api;

import com.Django.TraceChain.dto.*;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@CrossOrigin(origins="http://localhost:5173")
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

        if (wallet.isNewlyFetched()) {
            wallet.setTransactions(walletService.getTransactions(chain, address));
        }

        return ResponseEntity.ok(DtoMapper.mapWallet(wallet));
    }

    @GetMapping("/search-limited")
    public ResponseEntity<WalletDto> searchLimited(@RequestParam String address,
                                                   @RequestParam(defaultValue = "bitcoin") String chain,
                                                   @RequestParam(defaultValue = "10") int limit) {
        Wallet wallet = walletService.findAddress(chain, address);

        if (wallet.isNewlyFetched()) {
            wallet.setTransactions(walletService.getTransactions(chain, address, limit));
        }

        return ResponseEntity.ok(DtoMapper.mapWallet(wallet));
    }


    @GetMapping("/trace")
    public ResponseEntity<Set<String>> trace(@RequestParam String address,
                                             @RequestParam(defaultValue = "bitcoin") String chain,
                                             @RequestParam(defaultValue = "0") int depth,
                                             @RequestParam(defaultValue = "2") int maxDepth) {
        Set<String> visited = new HashSet<>();
        walletService.traceAllTransactionsRecursive(chain, address, depth, maxDepth, visited);
        return ResponseEntity.ok(visited);
    }

    @GetMapping("/trace-limited")
    public ResponseEntity<Map<Integer, List<WalletDto>>> traceDetailed(@RequestParam String address,
                                                                       @RequestParam(defaultValue = "bitcoin") String chain,
                                                                       @RequestParam(defaultValue = "0") int depth,
                                                                       @RequestParam(defaultValue = "2") int maxDepth) {
        Set<String> visited = new HashSet<>();
        Map<Integer, List<Wallet>> depthMap = new TreeMap<>();

        walletService.traceLimitedTransactionsRecursive(chain, address, depth, maxDepth, depthMap, visited);

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
        List<Wallet> wallets = walletService.getAllWallets();
        detectService.runAllDetectors(wallets);
        List<WalletDto> results = wallets.stream()
                .map(DtoMapper::mapWallet)
                .collect(Collectors.toList());
        return ResponseEntity.ok(results);
    }

    @PostMapping("/detect-selected")
    public ResponseEntity<List<WalletDto>> detectSelected(@RequestBody List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<Wallet> wallets = addresses.stream()
                .map(walletService::findByIdSafe)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        detectService.runAllDetectors(wallets);

        List<WalletDto> results = wallets.stream()
                .map(DtoMapper::mapWallet)
                .collect(Collectors.toList());

        return ResponseEntity.ok(results);
    }

    @GetMapping("/search-by-time")
    public ResponseEntity<WalletDto> searchByTime(@RequestParam String address,
                                                  @RequestParam long start,
                                                  @RequestParam long end,
                                                  @RequestParam(defaultValue = "50") int limit,
                                                  @RequestParam(defaultValue = "bitcoin") String chain) {
        Wallet wallet = walletService.findAddress(chain, address);

        // 공통 메서드로 대체
        wallet.setTransactions(walletService.getTransactionsByTimeRange(chain, address, start, end, limit));

        return ResponseEntity.ok(DtoMapper.mapWallet(wallet));
    }

    @GetMapping("/trace-by-time")
    public ResponseEntity<List<WalletDto>> traceByTime(@RequestParam String address,
                                                       @RequestParam long start,
                                                       @RequestParam long end,
                                                       @RequestParam(defaultValue = "2") int maxDepth,
                                                       @RequestParam(defaultValue = "50") int limit,
                                                       @RequestParam(defaultValue = "bitcoin") String chain) {
        Set<String> visited = new HashSet<>();

        // 공통 메서드로 대체
        walletService.traceTransactionsByTimeRange(chain, address, 0, maxDepth, start, end, limit, visited);

        List<WalletDto> result = visited.stream()
                .map(addr -> walletService.findAddress(chain, addr))
                .filter(Objects::nonNull)
                .map(DtoMapper::mapWallet)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }


}
