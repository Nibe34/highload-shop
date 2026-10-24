package ua.highload.shop.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ua.highload.shop.api.Dtos.ProductRequest;
import ua.highload.shop.domain.Category;
import ua.highload.shop.domain.Product;
import ua.highload.shop.service.CategoryService;
import ua.highload.shop.service.ProductService;

@RestController
@RequestMapping("/api")
public class ProductController {

    private final ProductService products;
    private final CategoryService categories;

    public ProductController(ProductService products, CategoryService categories) {
        this.products = products;
        this.categories = categories;
    }

    @GetMapping("/categories")
    public List<Category> categories() {
        return categories.all();
    }

    @GetMapping("/products")
    public List<Product> list(@RequestParam(required = false) Long categoryId,
                              @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return products.list(categoryId, page, size);
    }

    @GetMapping("/products/{id}")
    public Product get(@PathVariable long id) {
        return products.get(id);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public Product create(@Valid @RequestBody ProductRequest req) {
        return products.create(req.categoryId(), req.name(), descriptionOf(req), req.priceCents(), req.stock());
    }

    @PutMapping("/products/{id}")
    public Product update(@PathVariable long id, @Valid @RequestBody ProductRequest req) {
        return products.update(id, req.categoryId(), req.name(), descriptionOf(req), req.priceCents(), req.stock());
    }

    @DeleteMapping("/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        products.delete(id);
    }

    private static String descriptionOf(ProductRequest req) {
        return req.description() == null ? "" : req.description();
    }
}
