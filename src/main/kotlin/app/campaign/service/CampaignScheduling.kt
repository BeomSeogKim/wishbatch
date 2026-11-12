package app.campaign.service

import app.campaign.store.OfferRepository
import app.campaign.store.CampaignRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.time.Clock

@Configuration
@EnableScheduling
class CampaignScheduling {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun campaignClock(): Clock = Clock.systemUTC()
}

@Component
class CampaignReconciliationTick(
    private val campaigns: CampaignRepository,
    private val offers: OfferRepository,
    private val service: CampaignService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun tick() {
        campaigns.findAll().forEach { campaign ->
            val offer = offers.findById(campaign.offerId).orElse(null) ?: return@forEach
            try {
                service.reconcile(CampaignRef(offer.workspaceId, campaign.id))
            } catch (_: Exception) {
                logger.warn("Campaign reconciliation interrupted: {}", campaign.id)
            }
        }
    }
}
