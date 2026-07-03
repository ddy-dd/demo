package com.example.demo.ai.db;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * SQLite 数据源配置
 * <p>
 * 使用文件型 SQLite 数据库，无需额外安装服务。
 * 数据库文件默认生成在项目运行目录下的 aidemo.db。
 * 可通过环境变量 APP_DB_PATH 自定义路径。
 * </p>
 */
@Configuration
public class SqliteConfig {

    @Value("${app.db.path:aidemo.db}")
    private String dbPath;

    @Bean
    public DataSource sqliteDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbPath);
        dataSource.setUsername("");
        dataSource.setPassword("");
        return dataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
