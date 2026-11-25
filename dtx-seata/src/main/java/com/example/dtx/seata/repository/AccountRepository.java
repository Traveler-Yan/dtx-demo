package com.example.dtx.seata.repository;

import com.example.dtx.common.BizException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;

/** Account storage, same database as stock in this demo. */
@Repository
public class AccountRepository {

    private final JdbcTemplate jdbc;

    public AccountRepository(@Qualifier("stockDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void debit(long userId, BigDecimal amount) {
        int updated = jdbc.update(
                "update account set balance = balance - ? where user_id = ? and balance >= ?",
                amount, userId, amount);
        if (updated == 0) {
            throw new BizException("BALANCE_NOT_ENOUGH", "user " + userId + " cannot pay " + amount);
        }
    }

    public BigDecimal balance(long userId) {
        return jdbc.queryForObject("select balance from account where user_id = ?", BigDecimal.class, userId);
    }
}
