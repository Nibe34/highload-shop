package ua.highload.shop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class TxConfig {

    /** Програмні транзакції в БД каталогу (Primary) — для кроків Saga. */
    @Bean
    TransactionTemplate catalogTx(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }
}
