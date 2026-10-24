package ua.highload.shop.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ua.highload.shop.domain.Product;

@Repository
public class ProductRepository {

    private static final String COLUMNS =
            "id, category_id, name, description, price_cents, stock, created_at, updated_at";

    private final NamedParameterJdbcTemplate jdbc;

    public ProductRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Product> findPage(Long categoryId, int page, int size) {
        var params = new MapSqlParameterSource()
                .addValue("categoryId", categoryId)
                .addValue("limit", size)
                .addValue("offset", page * size);
        String where = categoryId == null ? "" : "WHERE category_id = :categoryId";
        return jdbc.query("SELECT " + COLUMNS + " FROM products " + where + " ORDER BY id LIMIT :limit OFFSET :offset",
                params, ProductRepository::map);
    }

    public Optional<Product> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM products WHERE id = :id",
                new MapSqlParameterSource("id", id), ProductRepository::map).stream().findFirst();
    }

    /** Блокує рядки товарів у фіксованому порядку (ORDER BY id), щоб уникнути взаємоблокувань між замовленнями. */
    public List<Product> lockByIds(Collection<Long> ids) {
        return jdbc.query("SELECT " + COLUMNS + " FROM products WHERE id IN (:ids) ORDER BY id FOR UPDATE",
                new MapSqlParameterSource("ids", ids), ProductRepository::map);
    }

    public Product create(long categoryId, String name, String description, long priceCents, int stock) {
        var params = new MapSqlParameterSource()
                .addValue("categoryId", categoryId)
                .addValue("name", name)
                .addValue("description", description)
                .addValue("price", priceCents)
                .addValue("stock", stock);
        return jdbc.queryForObject("""
                INSERT INTO products (category_id, name, description, price_cents, stock)
                VALUES (:categoryId, :name, :description, :price, :stock)
                RETURNING""" + " " + COLUMNS, params, ProductRepository::map);
    }

    public Optional<Product> update(long id, long categoryId, String name, String description, long priceCents, int stock) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("categoryId", categoryId)
                .addValue("name", name)
                .addValue("description", description)
                .addValue("price", priceCents)
                .addValue("stock", stock);
        return jdbc.query("""
                UPDATE products
                SET category_id = :categoryId, name = :name, description = :description,
                    price_cents = :price, stock = :stock, updated_at = now()
                WHERE id = :id
                RETURNING""" + " " + COLUMNS, params, ProductRepository::map).stream().findFirst();
    }

    public void decrementStock(long id, int quantity) {
        jdbc.update("UPDATE products SET stock = stock - :qty, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("qty", quantity));
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM products WHERE id = :id", new MapSqlParameterSource("id", id)) > 0;
    }

    static Product map(ResultSet rs, int rowNum) throws SQLException {
        return new Product(rs.getLong("id"), rs.getLong("category_id"), rs.getString("name"),
                rs.getString("description"), rs.getLong("price_cents"), rs.getInt("stock"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
