package org.hspconsortium.platform.api.fhir;

import ca.uhn.fhir.jpa.config.BaseJavaConfigDstu2;
import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.jpa.util.SubscriptionsRequireManualActivationInterceptorDstu2;
import ca.uhn.fhir.rest.server.interceptor.IServerInterceptor;
import ca.uhn.fhir.rest.server.interceptor.LoggingInterceptor;
import ca.uhn.fhir.rest.server.interceptor.ResponseHighlighterInterceptor;
import org.apache.commons.lang3.time.DateUtils;
import org.hibernate.MultiTenancyStrategy;
import org.hibernate.cfg.Environment;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceBuilder;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.Properties;

@Configuration
@EnableTransactionManagement()
@PropertySource("classpath:/config/mysql.properties")
@EnableConfigurationProperties({JpaProperties.class, MultiTenantProperties.class})
@EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class})
public class MySQLConfig extends BaseJavaConfigDstu2 {
    @Autowired
    private MultiTenantProperties multitenancyProperties;

    @Value("${hspc.platform.api.fhir.hibernate.dialect}")
    private String hibernateDialect;

    @Value("${hibernate.search.default.indexBase}")
    private String luceneBase;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JpaProperties jpaProperties;

    @Autowired
    private MultiTenantConnectionProvider multiTenantConnectionProvider;

    @Autowired
    private CurrentTenantIdentifierResolver currentTenantIdentifierResolver;

    /**
     * Configure FHIR properties around the the JPA server via this bean
     */
    @Bean()
    public DaoConfig daoConfig() {
        DaoConfig retVal = new DaoConfig();
        retVal.setSubscriptionEnabled(true);
        retVal.setSubscriptionPollDelay(5000);
        retVal.setSubscriptionPurgeInactiveAfterMillis(DateUtils.MILLIS_PER_HOUR);
        retVal.setAllowMultipleDelete(true);
        return retVal;
    }

    @Bean(name = {"dataSource", "defaultDataSource"})
//    @ConfigurationProperties(prefix = "hspc.platform.api.fhir.db")
    public DataSource dataSource() {
        DataSourceBuilder factory = DataSourceBuilder
                .create(this.multitenancyProperties.getDb().getClassLoader())
                .driverClassName(this.multitenancyProperties.getDb().getDriverClassName())
                .username(this.multitenancyProperties.getDb().getUsername())
                .password(this.multitenancyProperties.getDb().getPassword())
                .url(this.multitenancyProperties.getDb().getUrl());
        return factory.build();
    }

    @Bean()
    public LocalContainerEntityManagerFactoryBean entityManagerFactory() {
        LocalContainerEntityManagerFactoryBean retVal = new LocalContainerEntityManagerFactoryBean();
        retVal.setDataSource(dataSource);
        retVal.setPackagesToScan("ca.uhn.fhir.jpa.entity");
        retVal.setPersistenceProvider(new HibernatePersistenceProvider());
        retVal.setJpaProperties(jpaProperties());
        retVal.afterPropertiesSet();
        return retVal;
    }

    private Properties jpaProperties() {
        Properties hibernateProps = new Properties();
        hibernateProps.putAll(jpaProperties.getHibernateProperties(dataSource));
        hibernateProps.put(Environment.SHOW_SQL, "false");
        hibernateProps.put(Environment.FORMAT_SQL, "true");
        hibernateProps.put(Environment.STATEMENT_BATCH_SIZE, "20");
        hibernateProps.put(Environment.USE_MINIMAL_PUTS, "false");
        hibernateProps.put(Environment.ORDER_INSERTS, "false");
        hibernateProps.put(Environment.ORDER_UPDATES, "false");
        hibernateProps.put(Environment.USE_QUERY_CACHE, "false");
        hibernateProps.put(Environment.USE_SECOND_LEVEL_CACHE, "false");
        hibernateProps.put(Environment.USE_STRUCTURED_CACHE, "false");
        hibernateProps.put(Environment.HBM2DDL_AUTO, "update");
        hibernateProps.put(Environment.MULTI_TENANT, MultiTenancyStrategy.DATABASE);
        hibernateProps.put(Environment.MULTI_TENANT_CONNECTION_PROVIDER, multiTenantConnectionProvider);
        hibernateProps.put(Environment.MULTI_TENANT_IDENTIFIER_RESOLVER, currentTenantIdentifierResolver);
        hibernateProps.put(Environment.DIALECT, hibernateDialect);
        hibernateProps.put(Environment.USE_MINIMAL_PUTS, "false");
        hibernateProps.put("hibernate.search.default.indexBase", luceneBase);
        hibernateProps.put("hibernate.search.lucene_version", "LUCENE_CURRENT");
        hibernateProps.put("hibernate.search.default.directory_provider", "filesystem");
        return hibernateProps;
    }

    /**
     * Do some fancy logging to create a nice access log that has details about each incoming request.
     */
    public IServerInterceptor loggingInterceptor() {
        LoggingInterceptor retVal = new LoggingInterceptor();
        retVal.setLoggerName("fhirtest.access");
        retVal.setMessageFormat(
                "Path[${servletPath}] Source[${requestHeader.x-forwarded-for}] Operation[${operationType} ${operationName} ${idOrResourceName}] UA[${requestHeader.user-agent}] Params[${requestParameters}] ResponseEncoding[${responseEncodingNoDefault}]");
        retVal.setLogExceptions(true);
        retVal.setErrorMessageFormat("ERROR - ${requestVerb} ${requestUrl}");
        return retVal;
    }

    /**
     * This interceptor adds some pretty syntax highlighting in responses when a browser is detected
     */
    @Bean(autowire = Autowire.BY_TYPE)
    public IServerInterceptor responseHighlighterInterceptor() {
        ResponseHighlighterInterceptor retVal = new ResponseHighlighterInterceptor();
        return retVal;
    }

    @Bean(autowire = Autowire.BY_TYPE)
    public IServerInterceptor subscriptionSecurityInterceptor() {
        return new SubscriptionsRequireManualActivationInterceptorDstu2();
    }

    @Bean()
    public JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        JpaTransactionManager retVal = new JpaTransactionManager();
        retVal.setEntityManagerFactory(entityManagerFactory);
        return retVal;
    }

}
