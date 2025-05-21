/*
 * Controller - 사용자 요청을 받아서 Service에 전달
 */

package com.Django.TraceChain.controller;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.repository.WalletRepository;
import com.Django.TraceChain.service.*;

import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class Controller {

    private final WalletService walletService;
    private final DetectService detectService;
    private final WalletRepository walletRepository;

    @Autowired
    public Controller(WalletService walletService, DetectService detectService, WalletRepository walletRepository) {
        this.walletService = walletService;
        this.detectService = detectService;
        this.walletRepository = walletRepository;
    }

    private String detectChain(String address) {
        if (address == null) return "unknown";
        if (address.startsWith("0x") && address.length() == 42) return "ethereum";
        if (address.startsWith("1") || address.startsWith("3") || address.startsWith("bc1")) return "bitcoin";
        return "unknown";
    }

    private Wallet ensureEthereumWalletSaved(String address, int depth, int maxDepth) {
        Set<String> visited = new HashSet<>();
        Map<Integer, List<Wallet>> depthMap = new TreeMap<>();
        ChainClient client = walletService.resolveClient("ethereum");
        if (client instanceof EthereumClient ethClient) {
            ethClient.traceLimitedTransactionsRecursive(address, depth, maxDepth, depthMap, visited);
        }
        return walletRepository.findById(address).orElse(null);
    }

    @GetMapping(value = "/search", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> search(@RequestParam(required = false) String address,
                                         @RequestParam(defaultValue = "bitcoin") String chain) {
        if (address == null || address.isEmpty()) {
            return ResponseEntity.badRequest().body("<html><body><h3>Address is required.</h3></body></html>");
        }

        if (chain == null || chain.isEmpty()) {
            chain = detectChain(address);
            if (chain.equals("unknown")) {
                return ResponseEntity.badRequest().body("<html><body><h3>Cannot detect chain type from address.</h3></body></html>");
            }
        }

        Wallet wallet;
        List<Transaction> txList;

        if (chain.equals("ethereum")) {
            wallet = ensureEthereumWalletSaved(address, 0, 0);
            txList = wallet != null ? wallet.getTransactions() : List.of();
        } else {
            wallet = walletService.findAddress(chain, address);
            txList = walletService.getTransactions(chain, address);
        }

        if (wallet == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("<html><body><h3>Wallet not found or API error.</h3></body></html>");
        }

        StringBuilder html = new StringBuilder();
        html.append("<html><body>");
        html.append("<h2>Wallet Address: ").append(wallet.getAddress()).append("</h2>");
        html.append("<p>Balance: ").append(wallet.getBalance()).append("</p>");
        html.append("<h3>Transactions:</h3>");

        for (Transaction tx : txList) {
            html.append("<div style='margin-bottom:15px;'>");
            html.append("<strong>TxID:</strong> ").append(tx.getTxID()).append("<br>");
            html.append("<strong>Amount:</strong> ").append(tx.getAmount()).append("<br>");
            html.append("<strong>Timestamp:</strong> ").append(tx.getTimestamp()).append("<br>");
            html.append("<ul>");
            for (Transfer t : tx.getTransfers()) {
                html.append("<li>");
                if (t.getSender() != null) html.append("From: ").append(t.getSender()).append(" → ");
                if (t.getReceiver() != null) html.append("To: ").append(t.getReceiver()).append(" ");
                html.append("Amount: ").append(t.getAmount());
                html.append("</li>");
            }
            html.append("</ul></div>");
        }

        html.append("</body></html>");
        return ResponseEntity.ok(html.toString());
    }

    @GetMapping(value = "/search-limited", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> searchLimited(@RequestParam String address,
                                                @RequestParam(defaultValue = "bitcoin") String chain,
                                                @RequestParam(defaultValue = "10") int limit) {
        if (address == null || address.isEmpty()) {
            return ResponseEntity.badRequest().body("<html><body><h3>Address is required.</h3></body></html>");
        }

        Wallet wallet;
        List<Transaction> txList;

        if (chain.equals("ethereum")) {
            wallet = ensureEthereumWalletSaved(address, 0, 0);
            txList = wallet != null ? wallet.getTransactions().stream().limit(limit).toList() : List.of();
        } else {
            wallet = walletService.findAddress(chain, address);
            txList = walletService.getTransactions(chain, address, limit);
        }

        if (wallet == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("<html><body><h3>Wallet not found or API error.</h3></body></html>");
        }

        StringBuilder html = new StringBuilder();
        html.append("<html><body>");
        html.append("<h2>Wallet Address: ").append(wallet.getAddress()).append("</h2>");
        html.append("<p>Balance: ").append(wallet.getBalance()).append("</p>");
        html.append("<h3>Recent ").append(limit).append(" Transactions:</h3>");

        for (Transaction tx : txList) {
            html.append("<div style='margin-bottom:15px;'>");
            html.append("<strong>TxID:</strong> ").append(tx.getTxID()).append("<br>");
            html.append("<strong>Amount:</strong> ").append(tx.getAmount()).append("<br>");
            html.append("<strong>Timestamp:</strong> ").append(tx.getTimestamp()).append("<br>");
            html.append("<ul>");
            for (Transfer t : tx.getTransfers()) {
                html.append("<li>");
                html.append("From: ").append(t.getSender()).append(" → ");
                html.append("To: ").append(t.getReceiver()).append(" | ");
                html.append("Amount: ").append(t.getAmount());
                html.append("</li>");
            }
            html.append("</ul></div>");
        }

        html.append("</body></html>");
        return ResponseEntity.ok(html.toString());
    }

    @GetMapping(value = "/trace", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> trace(@RequestParam String address,
                                        @RequestParam(defaultValue = "bitcoin") String chain,
                                        @RequestParam(defaultValue = "0") int depth,
                                        @RequestParam(defaultValue = "2") int maxDepth) {
        if (address == null || address.isEmpty()) {
            return ResponseEntity.badRequest().body("<html><body><h3>Address is required.</h3></body></html>");
        }

        Set<String> visited = new HashSet<>();

        walletService.traceAllTransactionsRecursive(chain, address, depth, maxDepth, visited);

        StringBuilder html = new StringBuilder();
        html.append("<html><body>");
        html.append("<h2>Recursive Trace for Address: ").append(address).append("</h2>");
        html.append("<p>Max Depth: ").append(maxDepth).append("</p>");
        html.append("<p>Total Unique Addresses Visited: ").append(visited.size()).append("</p>");
        html.append("<ul>");
        for (String addr : visited) {
            html.append("<li>").append(addr).append("</li>");
        }
        html.append("</ul></body></html>");

        return ResponseEntity.ok(html.toString());
    }

    @GetMapping(value = "/trace-detailed", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> traceDetailed(@RequestParam String address,
                                                @RequestParam(defaultValue = "bitcoin") String chain,
                                                @RequestParam(defaultValue = "0") int depth,
                                                @RequestParam(defaultValue = "2") int maxDepth) {
        Set<String> visited = new HashSet<>();
        Map<Integer, List<Wallet>> depthMap = new TreeMap<>();

        walletService.traceLimitedTransactionsRecursive(chain, address, depth, maxDepth, depthMap, visited);

        StringBuilder html = new StringBuilder();
        html.append("<html><head><style>");
        html.append("body { font-family: Arial; background-color: #f9f9f9; }");
        html.append("table { border-collapse: collapse; width: 100%; margin-bottom: 30px; }");
        html.append("th, td { border: 1px solid #ccc; padding: 8px; text-align: left; font-size: 14px; }");
        html.append("th { background-color: #f2f2f2; }");
        html.append("h2, h3 { color: #333; }");
        html.append("</style></head><body>");

        html.append("<h2>Detailed Trace for Address: ").append(address).append("</h2>");
        html.append("<p>Total Unique Addresses Visited: ").append(visited.size()).append("</p>");

        for (Map.Entry<Integer, List<Wallet>> entry : depthMap.entrySet()) {
            html.append("<h3>Depth ").append(entry.getKey()).append("</h3>");
            html.append("<table>");
            html.append("<tr><th>Sender</th><th>TxID</th><th>Receiver</th><th>Amount (wei)</th><th>Direction</th><th>Timestamp</th></tr>");

            for (Wallet w : entry.getValue()) {
                List<Transaction> txs = w.getTransactions();

                if (txs == null || txs.isEmpty()) {
                    html.append("<tr>");
                    html.append("<td colspan='6'>No transactions for ").append(shortWithTooltip(w.getAddress())).append("</td>");
                    html.append("</tr>");
                    continue;
                }

                for (Transaction tx : txs) {
                    for (Transfer t : tx.getTransfers()) {
                        html.append("<tr>");
                        html.append("<td>").append(shortWithTooltip(t.getSender())).append("</td>");
                        html.append("<td>").append(shortWithTooltip(tx.getTxID())).append("</td>");
                        html.append("<td>").append(shortWithTooltip(t.getReceiver())).append("</td>");
                        html.append("<td>").append(t.getAmount()).append("</td>");

                        String direction = "-";
                        if (w.getAddress().equalsIgnoreCase(t.getReceiver())) direction = "IN";
                        else if (w.getAddress().equalsIgnoreCase(t.getSender())) direction = "OUT";

                        html.append("<td>").append(direction).append("</td>");
                        html.append("<td>").append(tx.getTimestamp() != null ? tx.getTimestamp().toString() : "-").append("</td>");
                        html.append("</tr>");
                    }
                }
            }

            html.append("</table>");
        }

        html.append("</body></html>");
        return ResponseEntity.ok(html.toString());
    }

    private String shortWithTooltip(String full) {
        if (full == null || full.length() <= 12) return full;
        String shorted = full.substring(0, 6) + "..." + full.substring(full.length() - 4);
        return "<abbr title=\"" + full + "\">" + shorted + "</abbr>";
    }

}