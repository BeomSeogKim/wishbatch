package app.web.home

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class HomeController {

    @GetMapping("/")
    fun home(): String = "home/index"

    @GetMapping("/styleguide")
    fun styleguide(): String = "home/styleguide"
}
