package app.campaign.service

import app.campaign.store.WorkspaceRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
class CampaignPersistence(
    manager: PlatformTransactionManager,
    private val workspaces: WorkspaceRepository,
) {
    private val coordinator = ReentrantLock(true)
    private val transaction = TransactionTemplate(manager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    // The outer transaction owns only the workspace lock. Payment checkpoints commit independently
    // so a process failure cannot erase evidence of an operation already applied by the provider.
    fun <T : Any> locked(workspaceId: UUID, action: () -> ServiceResult<T>): ServiceResult<T> =
        coordinator.withLock {
            // Local waiters must not exhaust the pool while the lock owner needs a checkpoint connection.
            transaction.execute {
                if (workspaces.lockById(workspaceId) == null) ServiceResult.Rejected(Rejection.NOT_FOUND)
                else action()
            }!!
        }

    fun <T> checkpoint(action: () -> T): T = transaction.execute { Value(action()) }!!.value

    private data class Value<T>(val value: T)
}
