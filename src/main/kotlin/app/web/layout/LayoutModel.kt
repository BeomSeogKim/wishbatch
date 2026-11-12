package app.web.layout

import app.startup.TemporaryDatabaseStartup
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

@ControllerAdvice(basePackages = ["app.web"])
class LayoutModel(
    @Value("\${PRODUCT_NAME:Wishbatch}") private val productName: String,
    private val databaseStartup: ObjectProvider<TemporaryDatabaseStartup>,
) {

    @ModelAttribute("productName")
    fun productName(): String = productName

    @ModelAttribute("temporaryDatabase")
    fun temporaryDatabase(): Boolean = databaseStartup.ifAvailable?.temporary == true
}
