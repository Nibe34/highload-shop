package ua.highload.shop.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ua.highload.shop.cache.CacheClient;
import ua.highload.shop.cache.CacheKeys;
import ua.highload.shop.cache.Cached;
import ua.highload.shop.domain.NotFoundException;
import ua.highload.shop.domain.Product;
import ua.highload.shop.repo.ProductRepository;

@Service
public class ProductService {

    private final ProductRepository products;
    private final CacheClient cache;
    private final JavaType productList;
    private final Duration productTtl;
    private final Duration catalogTtl;

    public ProductService(ProductRepository products, CacheClient cache, ObjectMapper json,
                          @Value("${shop.cache.ttl.product:5m}") Duration productTtl,
                          @Value("${shop.cache.ttl.catalog:60s}") Duration catalogTtl) {
        this.products = products;
        this.cache = cache;
        this.productList = json.getTypeFactory().constructCollectionType(List.class, Product.class);
        this.productTtl = productTtl;
        this.catalogTtl = catalogTtl;
    }

    /** Сторінка каталогу: ключ містить версію категорії, тож будь-яка зміна товару категорії інвалідує всі її сторінки. */
    public Cached<List<Product>> list(Long categoryId, int page, int size) {
        long version = cache.version(CacheKeys.catalogVersion(categoryId));
        return cache.getOrLoad(CacheKeys.catalogPage(categoryId, version, page, size), productList, catalogTtl,
                () -> products.findPage(categoryId, page, size));
    }

    public Cached<Product> getCached(long id) {
        return cache.getOrLoad(CacheKeys.product(id), Product.class, productTtl,
                () -> products.findById(id).orElseThrow(() -> notFound(id)));
    }

    public Product get(long id) {
        return getCached(id).value();
    }

    public Product create(long categoryId, String name, String description, long priceCents, int stock) {
        Product created = products.create(categoryId, name, description, priceCents, stock);
        invalidateCatalog(categoryId);
        return created;
    }

    public Product update(long id, long categoryId, String name, String description, long priceCents, int stock) {
        Long oldCategory = products.findById(id).map(Product::categoryId).orElse(null);
        Product updated = products.update(id, categoryId, name, description, priceCents, stock)
                .orElseThrow(() -> notFound(id));
        cache.evict(List.of(CacheKeys.product(id)));
        invalidateCatalog(categoryId);
        if (oldCategory != null && oldCategory != categoryId) {
            invalidateCatalog(oldCategory);
        }
        return updated;
    }

    public void delete(long id) {
        Long category = products.findById(id).map(Product::categoryId).orElseThrow(() -> notFound(id));
        if (!products.delete(id)) {
            throw notFound(id);
        }
        cache.evict(List.of(CacheKeys.product(id)));
        invalidateCatalog(category);
    }

    /** Інвалідація карток товарів після зміни залишків (checkout). Сторінки каталогу живуть до TTL. */
    public void evictProducts(Collection<Long> ids) {
        var keys = new ArrayList<String>(ids.size());
        ids.forEach(id -> keys.add(CacheKeys.product(id)));
        cache.evict(keys);
    }

    private void invalidateCatalog(long categoryId) {
        cache.bumpVersion(CacheKeys.catalogVersion(categoryId));
        cache.bumpVersion(CacheKeys.catalogVersion(null));
    }

    private static NotFoundException notFound(long id) {
        return new NotFoundException("Product " + id + " not found");
    }
}
