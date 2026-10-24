package ua.highload.shop.service;

import java.util.List;

import org.springframework.stereotype.Service;
import ua.highload.shop.domain.NotFoundException;
import ua.highload.shop.domain.Product;
import ua.highload.shop.repo.ProductRepository;

@Service
public class ProductService {

    private final ProductRepository products;

    public ProductService(ProductRepository products) {
        this.products = products;
    }

    public List<Product> list(Long categoryId, int page, int size) {
        return products.findPage(categoryId, page, size);
    }

    public Product get(long id) {
        return products.findById(id).orElseThrow(() -> notFound(id));
    }

    public Product create(long categoryId, String name, String description, long priceCents, int stock) {
        return products.create(categoryId, name, description, priceCents, stock);
    }

    public Product update(long id, long categoryId, String name, String description, long priceCents, int stock) {
        return products.update(id, categoryId, name, description, priceCents, stock).orElseThrow(() -> notFound(id));
    }

    public void delete(long id) {
        if (!products.delete(id)) {
            throw notFound(id);
        }
    }

    private static NotFoundException notFound(long id) {
        return new NotFoundException("Product " + id + " not found");
    }
}
