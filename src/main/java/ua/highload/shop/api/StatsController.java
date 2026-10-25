package ua.highload.shop.api;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.highload.shop.api.Dtos.SummaryResponse;
import ua.highload.shop.domain.TopProduct;
import ua.highload.shop.service.StatsService;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService stats;
    private final String instanceId;

    public StatsController(StatsService stats, @Value("${shop.instance-id}") String instanceId) {
        this.stats = stats;
        this.instanceId = instanceId;
    }

    @GetMapping("/top-products")
    public ResponseEntity<List<TopProduct>> topProducts(@RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        return ProductController.withCacheHeader(stats.topProducts(limit));
    }

    @GetMapping("/summary")
    public SummaryResponse summary() {
        return new SummaryResponse(stats.ordersCreated(), instanceId);
    }
}
