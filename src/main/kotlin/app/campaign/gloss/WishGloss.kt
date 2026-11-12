package app.campaign.gloss

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "wish_glosses", uniqueConstraints = [UniqueConstraint(columnNames = ["workspace_id", "wish_id", "source_hash"])])
class WishGloss(
    @Column(name = "workspace_id", nullable = false, updatable = false) val workspaceId: UUID,
    @Column(name = "wish_id", nullable = false, updatable = false) val wishId: UUID,
    @Column(name = "source_hash", nullable = false, updatable = false, length = 64) val sourceHash: String,
    @Column(nullable = false) var attempts: Int = 0,
    @Enumerated(EnumType.STRING) var status: GlossStatus? = null,
    @Column(length = 40) var inferredLanguage: String? = null,
    @Column(length = 1500) var englishGloss: String? = null,
    @Column(length = 120) var model: String? = null,
    var generatedAt: Instant? = null,
    @Id val id: UUID = UUID.randomUUID(),
) {
    val label: String get() = "Machine translation"
}
