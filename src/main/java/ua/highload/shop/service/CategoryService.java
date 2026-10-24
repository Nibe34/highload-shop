package ua.highload.shop.service;

import java.util.List;

import org.springframework.stereotype.Service;
import ua.highload.shop.domain.Category;
import ua.highload.shop.repo.CategoryRepository;

@Service
public class CategoryService {

    private final CategoryRepository categories;

    public CategoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    public List<Category> all() {
        return categories.findAll();
    }
}
