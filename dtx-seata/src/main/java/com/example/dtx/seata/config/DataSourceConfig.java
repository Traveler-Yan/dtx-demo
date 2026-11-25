package com.example.dtx.seata.config;

import io.seata.rm.datasource.DataSourceProxy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Two physical data sources, each wrapped in a Seata {@link DataSourceProxy}.
 *
 * <p>The proxy is what makes AT work: it parses the SQL, snapshots the rows
 * before/after the statement, writes {@code undo_log} in the same local
 * transaction and registers the branch with the TC. Auto proxying is disabled
 * in {@code application.yml} because we build the proxies explicitly.</p>
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("app.datasource.order")
    public DataSourceProperties orderDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("app.datasource.stock")
    public DataSourceProperties stockDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource orderRawDataSource() {
        return orderDataSourceProperties().initializeDataSourceBuilder().build();
    }

    @Bean
    public DataSource stockRawDataSource() {
        return stockDataSourceProperties().initializeDataSourceBuilder().build();
    }

    @Bean
    @Primary
    public DataSource orderDataSource(@Qualifier("orderRawDataSource") DataSource raw) {
        return new DataSourceProxy(raw);
    }

    @Bean
    public DataSource stockDataSource(@Qualifier("stockRawDataSource") DataSource raw) {
        return new DataSourceProxy(raw);
    }
}
