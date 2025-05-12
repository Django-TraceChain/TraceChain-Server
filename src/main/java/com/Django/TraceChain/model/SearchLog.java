package com.Django.TraceChain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "search_logs")
public class SearchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;  // 기본키

    private String address;  // 검색한 지갑 주소

    private LocalDateTime searchedAt;  // 검색 시간

    // 기본 생성자
    public SearchLog() {}

    public SearchLog(String address, LocalDateTime searchedAt) {
        this.address = address;
        this.searchedAt = searchedAt;
    }

    // Getter/Setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public LocalDateTime getSearchedAt() { return searchedAt; }
    public void setSearchedAt(LocalDateTime searchedAt) { this.searchedAt = searchedAt; }
}
