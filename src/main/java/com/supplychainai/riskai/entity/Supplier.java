package com.supplychainai.riskai.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(
        name = "suppliers",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_supplier_auth_provider_external_id",
                columnNames = {"auth_provider", "external_id"}))
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "trade_name", length = 255)
    private String tradeName;

    @Column
    private String address;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "gstin", length = 15)
    private String gstin;

    @Column(name = "pan", length = 10)
    private String pan;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SupplierRole role = SupplierRole.SUPPLIER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SupplierVerificationStatus verificationStatus = SupplierVerificationStatus.PENDING;

    @Column(name = "verification_notes", columnDefinition = "TEXT")
    private String verificationNotes;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "google_id", unique = true, length = 255)
    private String googleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", length = 32)
    private AuthProvider authProvider;

    @Column(name = "external_id", length = 255)
    private String externalId;
}
