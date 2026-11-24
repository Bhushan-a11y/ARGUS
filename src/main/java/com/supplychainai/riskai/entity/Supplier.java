package com.supplychainai.riskai.entity;

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

    @Column
    private String address;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SupplierRole role = SupplierRole.SUPPLIER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SupplierVerificationStatus verificationStatus = SupplierVerificationStatus.PENDING;
    @Column(name = "google_id", unique = true, length = 255)
    private String googleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", length = 32)
    private AuthProvider authProvider;

    @Column(name = "external_id", length = 255)
    private String externalId;
}
