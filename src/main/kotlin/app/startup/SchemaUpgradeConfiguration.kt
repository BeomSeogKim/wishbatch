package app.startup

import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
class SchemaUpgradeConfiguration {
    @Bean
    fun schemaUpgrade(dataSource: DataSource) = InitializingBean {
        ExistingSchemaUpgrade(dataSource).upgrade()
    }

    companion object {
        @Bean
        @JvmStatic
        fun entityManagerFactorySchemaDependency() = EntityManagerFactoryDependsOnPostProcessor("schemaUpgrade")
    }
}
