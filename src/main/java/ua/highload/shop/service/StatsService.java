package ua.highload.shop.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;
import ua.highload.shop.domain.TopProduct;
import ua.highload.shop.repo.OrderRepository;

@Service
public class StatsService {

    /** Лічильник оформлених замовлень з моменту старту (MVP, локальний для процесу). */
    private final AtomicLong ordersCreated = new AtomicLong();
    private final OrderRepository orders;

    public StatsService(OrderRepository orders) {
        this.orders = orders;
    }

    public void orderCreated() {
        ordersCreated.incrementAndGet();
    }

    public long ordersCreated() {
        return ordersCreated.get();
    }

    public List<TopProduct> topProducts(int limit) {
        return orders.topProducts(limit);
    }
}
