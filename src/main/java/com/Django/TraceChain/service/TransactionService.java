/*package com.Django.TraceChain.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.Django.TraceChain.Repository.TransactionRepository;
import com.Django.TraceChain.model.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class TransactionService {

	private final AccessToken accessToken;

    @Value("${blockstream.api-url}")
    private String apiUrl;
    
    @Autowired
    private TransactionService TransactionRepository;
    
    @Autowired
    public TransactionService(AccessToken accessToken) {
        this.accessToken = accessToken;
    }

}*/
