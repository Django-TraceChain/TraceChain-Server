package com.Django.TraceChain.controller;

import com.Django.TraceChain.model.Transaction;
import com.Django.TraceChain.model.Transfer;
import com.Django.TraceChain.model.Wallet;
import com.Django.TraceChain.service.AccessToken;
import com.Django.TraceChain.service.AddressService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class BlockstreamController {

    private final AccessToken accessTokenService;
    private final AddressService addressService;

    private String cachedAccessToken;

    @Autowired
    public BlockstreamController(AccessToken accessTokenService, AddressService addressService) {
        this.accessTokenService = accessTokenService;
        this.addressService = addressService;
    }

    private void initializeAccessToken() {
        if (cachedAccessToken == null || cachedAccessToken.isEmpty()) {
            System.out.println("Fetching Access Token...");
            cachedAccessToken = accessTokenService.getAccessToken();
            System.out.println("Access Token cached: " + cachedAccessToken);
        }
    }

    @GetMapping(value = "/search", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> search(@RequestParam(required = false) String address) {
        // access token 초기화
        initializeAccessToken();

        // 토큰이 없으면 에러 반환
        if (cachedAccessToken == null || cachedAccessToken.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Failed to retrieve access token.");
        }

     // 주소 기반 조회
        if (address != null && !address.isEmpty()) {
            // 주소를 통해 Wallet 객체 찾기
            Wallet wallet = addressService.findAddress(address);
            List<Transaction> transactions = addressService.findTxByAddress(address);

            if (wallet == null) {
                // Wallet이 없으면 찾을 수 없다는 메시지 출력
                return ResponseEntity.ok("<pre>해당 주소로 찾은 지갑이 없습니다.</pre>");
            }

            // Wallet 객체를 HTML 형식으로 출력
            StringBuilder htmlResponse = new StringBuilder();
            htmlResponse.append("<pre>");
            htmlResponse.append("주소: ").append(wallet.getAddress()).append("\n");
            htmlResponse.append("지갑 유형: ").append(wallet.getType()).append("\n");
            htmlResponse.append("잔액: ").append(wallet.getBalance()).append(" satoshi\n");

            if (transactions != null && !transactions.isEmpty()) {
                htmlResponse.append("<pre>\n[트랜잭션 목록]\n");
                for (Transaction tx : transactions) {
                    htmlResponse.append("TXID: ").append(tx.getTxID()).append("\n");
                    htmlResponse.append("금액: ").append(tx.getAmount()).append(" BTC\n");
                    htmlResponse.append("시간: ").append(tx.getTimestamp()).append("\n");

                    // 트랜잭션에 포함된 입출금 정보 출력
                    if (tx.getTransfers() != null && !tx.getTransfers().isEmpty()) {
                        htmlResponse.append("[입출금 정보]\n");
                        for (Transfer transfer : tx.getTransfers()) {
                            if (transfer.getSender() != null && transfer.getReceiver() == null) {
                                // 송신자만 있을 때
                                htmlResponse.append(" 송신자: ").append(transfer.getSender()).append("\n");
                            } else if (transfer.getReceiver() != null && transfer.getSender() == null) {
                                // 수신자만 있을 때
                                htmlResponse.append(" 수신자: ").append(transfer.getReceiver()).append("\n");
                            } else {
                                // 송신자와 수신자 둘 다 있을 때
                                htmlResponse.append(" 송신자: ").append(transfer.getSender()).append("\n");
                                htmlResponse.append(" 수신자: ").append(transfer.getReceiver()).append("\n");
                            }
                            htmlResponse.append(" 금액: ").append(transfer.getAmount()).append(" satoshi\n");
                        }
                    } else {
                        htmlResponse.append("입출금 정보가 없습니다.\n");
                    }

                    htmlResponse.append("\n");
                }
                htmlResponse.append("</pre>");
            } else {
                htmlResponse.append("<pre>해당 주소에 대한 트랜잭션이 없습니다.</pre>");
            }

            htmlResponse.append("</pre>");

            return ResponseEntity.ok()
                    .header("Content-Type", "text/html; charset=UTF-8")
                    .body(htmlResponse.toString());
        }

        // 파라미터가 없을 경우 에러 메시지 반환
        return ResponseEntity.badRequest().body("address를 반드시 포함되어야 합니다.");

    }

}
