package com.commercecore.backend.catalog.service;

import com.commercecore.backend.catalog.api.dto.CreateProductRequest;
import com.commercecore.backend.catalog.api.dto.ProductResponse;
import com.commercecore.backend.catalog.domain.Product;
import com.commercecore.backend.catalog.repo.ProductRepository;
import com.commercecore.backend.inventory.repo.InventoryRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    public ProductService(ProductRepository productRepository,
                          InventoryRepository inventoryRepository) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {
        Product product = new Product(
                request.sku(),
                request.name(),
                request.description(),
                request.price(),
                request.currency(),
                true  // active by default
        );
        product = productRepository.saveAndFlush(product);

        // Create initial inventory record atomically
        inventoryRepository.createInitialInventory(product.getProductId(), request.initialStock());

        return toResponse(product, request.initialStock());
    }

    public List<ProductResponse> listActiveProducts(int page, int size) {
        return productRepository.findAll().stream()
                .filter(Product::getActive)
                .map(p -> toResponse(p, inventoryRepository.getAvailableStock(p.getProductId())))
                .skip((long) page * size)
                .limit(size)
                .toList();
    }

    public ProductResponse getProduct(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + id));
        return toResponse(product, inventoryRepository.getAvailableStock(id));
    }

    private ProductResponse toResponse(Product p, Integer availableStock) {
        return new ProductResponse(
                p.getProductId(),
                p.getSku(),
                p.getName(),
                p.getDescription(),
                p.getPrice(),
                p.getCurrency(),
                availableStock,
                p.getActive()
        );
    }
}