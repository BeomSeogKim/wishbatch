package app.web.home

import app.campaign.workspace.SeedCatalog
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class HomeController(private val catalog: SeedCatalog) {

    @GetMapping("/")
    fun home(model: Model): String {
        model.addAttribute("scenarios", catalog.scenarios)
        return "home/index"
    }

    @GetMapping("/styleguide")
    fun styleguide(): String = "home/styleguide"
}
