package com.supplychainai.riskai.repository;

import com.supplychainai.riskai.entity.Supplier;
import com.supplychainai.riskai.entity.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {
    Optional<Supplier> findByEmail(String email);
    Optional<Supplier> findByGoogleId(String googleId);
    Optional<Supplier> findByEmailIgnoreCase(String email);
    Optional<Supplier> findByAuthProviderAndExternalId(AuthProvider authProvider, String externalId);
}
