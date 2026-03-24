package com.wrbug.polymarketbot.service.accounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * WCOL 解包轮询任务
 * 每 20 秒轮询一次，遍历所有账户的代理地址：若 WCOL 余额 > 0 则解包为 USDC.e。
 * 同一时间仅允许单次执行；若上次执行未结束则本次忽略（与现有轮询逻辑一致）。
 * 账户未配置可用 Builder 凭证时会在账户服务内逐个跳过。
 */
@Service
class WcolUnwrapJobService(
    private val accountService: AccountService
) {
    private val logger = LoggerFactory.getLogger(WcolUnwrapJobService::class.java)
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var unwrapJob: Job? = null

    /**
     * 每 20 秒触发一次；若当前任务仍在执行则跳过本次
     */
    @Scheduled(fixedRate = 20_000)
    fun runWcolUnwrapPolling() {
        if (unwrapJob?.isActive == true) {
            logger.debug("上一轮 WCOL 解包任务仍在执行，跳过本次")
            return
        }
        unwrapJob = scope.launch {
            try {
                accountService.runWcolUnwrapForAllAccounts()
            } catch (e: Exception) {
                logger.error("WCOL 解包轮询异常: ${e.message}", e)
            } finally {
                unwrapJob = null
            }
        }
    }
}
