package app.web.maker

import app.campaign.workspace.WishNotFoundException
import app.campaign.workspace.WorkspaceNotFoundException
import app.campaign.workspace.WorkspaceService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.UUID

@Controller
class MakerStartController(private val workspaces: WorkspaceService) {

    @PostMapping("/demo")
    fun startDemo(): String = "redirect:/w/${workspaces.create().token}/maker"

    @GetMapping("/w/{token}/maker")
    fun home(@PathVariable token: String, model: Model): String {
        model.addAttribute("workspace", workspaces.findByToken(token) ?: throw WorkspaceNotFoundException())
        model.addAttribute("fanOfferUrl", null)
        return "maker/home"
    }

    @GetMapping("/w/{token}/maker/options")
    fun options(@PathVariable token: String, model: Model): String {
        val options = workspaces.listOptions(token)
        model.addAttribute("options", options)
        model.addAttribute("unitCosts", options.associate { option ->
            val currency = Currency.getInstance(option.currency)
            val amount = BigDecimal.valueOf(option.unitCost, currency.defaultFractionDigits)
            val format = NumberFormat.getCurrencyInstance(Locale.US).apply { this.currency = currency }
            option.id to "${option.currency} ${format.format(amount)}"
        })
        return "maker/options"
    }

    @GetMapping("/w/{token}/maker/wishes")
    fun wishes(@PathVariable token: String, model: Model): String {
        model.addAttribute("wishes", workspaces.listWishes(token))
        model.addAttribute("visibleWishCount", workspaces.listWishes(token, includeHidden = false).size)
        return "maker/wishes"
    }

    @PostMapping("/w/{token}/maker/wishes")
    fun setWishHidden(
        @PathVariable token: String,
        @RequestParam wishId: UUID,
        @RequestParam hidden: Boolean,
    ): String {
        workspaces.setWishHidden(token, wishId, hidden)
        return "redirect:/w/$token/maker/wishes"
    }

    @ExceptionHandler(WorkspaceNotFoundException::class, WishNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/not-found"
}
