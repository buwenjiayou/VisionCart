package com.visioncart.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "recognition_history_product", indexes = {
    @Index(name = "idx_rhp_history", columnList = "history_id"),
    @Index(name = "idx_rhp_history_sort", columnList = "history_id, sort_no")
})
public class RecognitionHistoryProduct {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "history_id", nullable = false, length = 64)
    private String historyId;

    @Column(name = "product_id", nullable = false, length = 128)
    private String productId;

    @Column(name = "sort_no", nullable = false)
    private int sortNo;

    @Column(name = "similarity_score", precision = 8, scale = 6)
    private BigDecimal similarityScore;

    @Column(length = 500)
    private String title;

    @Column(name = "cover_image", length = 1000)
    private String coverImage;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "original_price", precision = 10, scale = 2)
    private BigDecimal originalPrice;

    @Column(length = 32)
    private String platform;

    @Column(length = 100)
    private String brand;

    @Column(precision = 3, scale = 2)
    private BigDecimal rating;

    private Integer sales;

    @Column(name = "detail_url", length = 1000)
    private String detailUrl;

    @Column(name = "snapshot_json", columnDefinition = "JSON")
    private String snapshotJson;

    @Column(name = "main_category_code", length = 64)
    private String mainCategoryCode;

    @Column(name = "product_role", length = 32)
    private String productRole;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // ========== Getters & Setters ==========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getHistoryId() { return historyId; }
    public void setHistoryId(String historyId) { this.historyId = historyId; }

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public int getSortNo() { return sortNo; }
    public void setSortNo(int sortNo) { this.sortNo = sortNo; }

    public BigDecimal getSimilarityScore() { return similarityScore; }
    public void setSimilarityScore(BigDecimal similarityScore) { this.similarityScore = similarityScore; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getCoverImage() { return coverImage; }
    public void setCoverImage(String coverImage) { this.coverImage = coverImage; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(BigDecimal originalPrice) { this.originalPrice = originalPrice; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public BigDecimal getRating() { return rating; }
    public void setRating(BigDecimal rating) { this.rating = rating; }

    public Integer getSales() { return sales; }
    public void setSales(Integer sales) { this.sales = sales; }

    public String getDetailUrl() { return detailUrl; }
    public void setDetailUrl(String detailUrl) { this.detailUrl = detailUrl; }

    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }

    public String getMainCategoryCode() { return mainCategoryCode; }
    public void setMainCategoryCode(String mainCategoryCode) { this.mainCategoryCode = mainCategoryCode; }

    public String getProductRole() { return productRole; }
    public void setProductRole(String productRole) { this.productRole = productRole; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
