package app.campaign.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnDefault
import java.time.Instant
import java.util.UUID

/** A statement of interest, with no payment or ordered quantity implied. */
@Entity
@Table(name = "wishes")
class Wish(
    @Column(nullable = false)
    val workspaceId: UUID,
    @Column(nullable = false, length = 500)
    val text: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val source: WishSource,
    val displayName: String? = null,
    @Column(nullable = false)
    val createdAt: Instant,
    @Column(nullable = false)
    var hidden: Boolean = false,
    @Id
    val id: UUID = UUID.randomUUID(),
    /** Additional imported occurrences, never additional fans or ordered units. */
    @Column(nullable = false)
    @ColumnDefault("0")
    var duplicateCount: Int = 0,
)
