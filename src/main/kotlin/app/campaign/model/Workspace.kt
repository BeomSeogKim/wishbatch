package app.campaign.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** The token is a public workspace locator, separate from the internal identifier. */
@Entity
@Table(name = "workspaces")
class Workspace(
    @Column(nullable = false, unique = true)
    val token: String,
    @Column(nullable = false)
    val createdAt: Instant,
    @Id
    val id: UUID = UUID.randomUUID(),
)
