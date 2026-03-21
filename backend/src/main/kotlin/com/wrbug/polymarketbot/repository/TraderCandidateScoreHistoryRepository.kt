package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.TraderCandidateScoreHistory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TraderCandidateScoreHistoryRepository : JpaRepository<TraderCandidateScoreHistory, Long> {
    fun findByAddressOrderByCreatedAtDesc(address: String, pageable: Pageable): Page<TraderCandidateScoreHistory>
}
