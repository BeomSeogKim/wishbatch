package app.web.layout

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

@ControllerAdvice(basePackages = ["app.web"])
class LayoutModel(@Value("\${PRODUCT_NAME:Wishbatch}") private val productName: String) {

    @ModelAttribute("productName")
    fun productName(): String = productName
}
