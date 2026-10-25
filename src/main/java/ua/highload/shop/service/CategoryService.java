package ua.highload.shop.service;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ua.highload.shop.cache.CacheClient;
import ua.highload.shop.cache.CacheKeys;
import ua.highload.shop.cache.Cached;
import ua.highload.shop.db.DbRouting;
import ua.highload.shop.domain.Category;
import ua.highload.shop.repo.CategoryRepository;

/** Довідник категорій у розподіленому кеші (на відміну від локального кешу ЛР1 — однаковий для всіх інстансів). */
@Service
public class CategoryService {

    private static final Duration TTL = Duration.ofHours(1);

    private final CategoryRepository categories;
    private final CacheClient cache;
    private final JavaType categoryList;

    public CategoryService(CategoryRepository categories, CacheClient cache, ObjectMapper json) {
        this.categories = categories;
        this.cache = cache;
        this.categoryList = json.getTypeFactory().constructCollectionType(List.class, Category.class);
    }

    public Cached<List<Category>> all() {
        return cache.getOrLoad(CacheKeys.categories(), categoryList, TTL, () -> DbRouting.replica(categories::findAll));
    }
}
