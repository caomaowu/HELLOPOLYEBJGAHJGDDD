package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.TraderCandidatePool
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository

@Repository
interface TraderCandidatePoolRepository : JpaRepository<TraderCandidatePool, Long>, JpaSpecificationExecutor<TraderCandidatePool> {
    fun findByAddress(address: String): TraderCandidatePool?
    fun findByAddressIn(addresses: Collection<String>): List<TraderCandidatePool>
}
