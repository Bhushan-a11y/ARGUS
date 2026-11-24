package com.supplychainai.riskai;

import org.springframework.web.bind.annotation.RestController;
import com.supplychainai.riskai.entity.Supplier;
import com.supplychainai.riskai.repository.SupplierRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/supplier") 
public class SupplierController {
    private final SupplierRepository supplierRepository;

    public SupplierController(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }
@GetMapping("/me")
public Supplier getCurrentSupplier(Authentication authentication) {
    System.out.println(">>> SupplierController.getCurrentSupplier reached");

    if (authentication == null || !authentication.isAuthenticated()) {
        throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authentication required"
        );
    }

    Long supplierId;

    try {
        supplierId = Long.valueOf(authentication.getName());
    } catch (NumberFormatException ex) {
        throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Invalid supplier identity in token"
        );
    }

    return supplierRepository.findById(supplierId)
            .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Supplier not found"
            ));
}

}


