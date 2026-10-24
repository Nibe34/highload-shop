package ua.highload.shop.service;

import java.util.List;

import org.springframework.stereotype.Service;
import ua.highload.shop.domain.Category;
import ua.highload.shop.repo.CategoryRepository;

/** Довідник категорій кешується локально при першому зверненні (MVP). */
@Service
public class CategoryService {

    private final CategoryRepository categories;
    private volatile List<Category> cached;

    public CategoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    public List<Category> all() {
        if (cached == null) {
            cached = categories.findAll();
        }
        return cached;
    }
}
