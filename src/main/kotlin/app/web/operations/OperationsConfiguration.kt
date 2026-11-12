package app.web.operations

import jakarta.servlet.DispatcherType
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

@Configuration(proxyBeanMethods = false)
class OperationsConfiguration {
    @Bean
    fun requestCorrelationFilter() = FilterRegistrationBean(RequestCorrelationFilter()).apply {
        order = Ordered.HIGHEST_PRECEDENCE + 5
        setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR)
    }
}
