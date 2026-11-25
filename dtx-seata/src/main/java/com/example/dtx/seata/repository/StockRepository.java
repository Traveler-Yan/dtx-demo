package com.example.dtx.seata.repository;

import com.example.dtx.common.BizException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;

/** Stock storage, lives in the second database. */
@Repository
public class StockRepository {

    private final JdbcTemplate jdbc;

    public StockRepository(@Qualifier("stockDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void deduct(String sku, int qty) {
        int updated = jdbc.update(
                "update storage set stock = stock - ? where sku = ? and stock >= ?", qty, sku, qty);
        if (updated == 0) {
            throw new BizException("STOCK_NOT_ENOUGH", "sku " + sku + " cannot deduct " + qty);
        }
    }

    public int stock(String sku) {
        Integer value = jdbc.queryForObject("select stock from storage where sku = ?", Integer.class, sku);
        return value == null ? -1 : value;
    }
}
