package ua.highload.shop.repo;

import java.util.List;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ua.highload.shop.domain.Category;

@Repository
public class CategoryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CategoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Category> findAll() {
        return jdbc.query("SELECT id, name FROM categories ORDER BY id",
                (rs, i) -> new Category(rs.getLong("id"), rs.getString("name")));
    }
}
