package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.ManualPositionHistoryRepository
import com.wrbug.polymarketbot.repository.PositionActivityLogRepository
import com.wrbug.polymarketbot.repository.PositionActivitySyncStateRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import java.util.Optional

class PositionActivityServiceTest {

    @Test
    fun `first sync should initialize state and skip historical backfill`() = runTest {
        val accountRepository = mock(AccountRepository::class.java)
        val manualPositionHistoryRepository = mock(ManualPositionHistoryRepository::class.java)
        val positionActivityLogRepository = mock(PositionActivityLogRepository::class.java)
        val syncStateRepository = mock(PositionActivitySyncStateRepository::class.java)
        val retrofitFactory = mock(RetrofitFactory::class.java)
        val cryptoUtils = mock(CryptoUtils::class.java)

        val account = Account(
            id = 1L,
            privateKey = "encrypted-private-key",
            walletAddress = "0x0000000000000000000000000000000000000001",
            proxyAddress = "0x0000000000000000000000000000000000000002",
            apiKey = "api-key",
            apiSecret = "encrypted-secret",
            apiPassphrase = "encrypted-passphrase"
        )
        `when`(accountRepository.findById(1L)).thenReturn(Optional.of(account))
        `when`(syncStateRepository.findByAccountId(1L)).thenReturn(null)

        val service = PositionActivityService(
            accountRepository = accountRepository,
            manualPositionHistoryRepository = manualPositionHistoryRepository,
            positionActivityLogRepository = positionActivityLogRepository,
            positionActivitySyncStateRepository = syncStateRepository,
            retrofitFactory = retrofitFactory,
            cryptoUtils = cryptoUtils
        )

        service.syncAccountActivitiesForAccountId(1L, quick = true)

        verify(syncStateRepository).findByAccountId(1L)
        verify(manualPositionHistoryRepository, never()).findByAccountIdOrderByCreatedAtAsc(1L)
    }
}
