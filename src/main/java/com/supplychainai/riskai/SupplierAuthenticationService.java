package com.supplychainai.riskai;

import java.util.Locale;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.supplychainai.riskai.entity.AuthProvider;
import com.supplychainai.riskai.entity.Supplier;
import com.supplychainai.riskai.entity.SupplierRole;
import com.supplychainai.riskai.entity.SupplierVerificationStatus;
import com.supplychainai.riskai.repository.SupplierRepository;


@Service
public class SupplierAuthenticationService {

    private final SupplierRepository supplierRepository;

    public SupplierAuthenticationService(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    @Transactional
    public Supplier authenticateOrRegister(
            AuthProvider provider,
            String externalId,
            String email,
            String name) {
        if (provider == null || !StringUtils.hasText(externalId) || !StringUtils.hasText(email)) {
            throw authenticationError("Required provider identity information is missing");
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        var existing = supplierRepository.findByAuthProviderAndExternalId(provider, externalId);
        if (existing.isPresent()) {
            return existing.get();
        }

        if (provider == AuthProvider.GOOGLE) {
            var googleAccount = supplierRepository.findByGoogleId(externalId);
            if (googleAccount.isPresent()) {
                Supplier supplier = googleAccount.get();
                supplier.setAuthProvider(provider);
                supplier.setExternalId(externalId);
                return supplierRepository.save(supplier);
            }
        }

        if (supplierRepository.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
            throw authenticationError(
                    "An account with this email already exists. Explicit account linking is required.");
        }

        Supplier supplier = new Supplier();
        supplier.setName(StringUtils.hasText(name) ? name.trim() : normalizedEmail);
        supplier.setEmail(normalizedEmail);
        supplier.setAuthProvider(provider);
        supplier.setExternalId(externalId);
        if (provider == AuthProvider.GOOGLE) {
            supplier.setGoogleId(externalId);
        }
        supplier.setRole(SupplierRole.SUPPLIER);
        supplier.setVerificationStatus(SupplierVerificationStatus.PENDING);

        return supplierRepository.save(supplier);
    }

    private OAuth2AuthenticationException authenticationError(String message) {
        return new OAuth2AuthenticationException(
                new OAuth2Error("supplier_authentication_failed"),
                message);
    }
}
