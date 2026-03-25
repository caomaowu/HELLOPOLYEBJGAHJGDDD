package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.AccountExecutionCheckDto
import com.wrbug.polymarketbot.dto.AccountSetupStatusDto
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.enums.WalletType
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.util.CryptoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.web3j.crypto.Credentials
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

@Service
class AccountExecutionDiagnosticsService(
    private val accountRepository: AccountRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val blockchainService: BlockchainService,
    private val orderSigningService: OrderSigningService,
    private val cryptoUtils: CryptoUtils
) {

    companion object {
        private const val CACHE_TTL_MS = 30_000L
        private const val HOT_PATH_STALE_SUCCESS_CACHE_TTL_MS = 10 * 60_000L
        private const val HOT_PATH_RECENT_FAILURE_CACHE_TTL_MS = 2 * 60_000L

        private val APPROVAL_SPENDERS = mapOf(
            "CTF_CONTRACT" to "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045",
            "CTF_EXCHANGE" to "0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E",
            "NEG_RISK_EXCHANGE" to "0xC5d563A36AE78145C45a50134d48A1215220f80a",
            "NEG_RISK_ADAPTER" to "0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296"
        )

        private val USDC_DECIMALS = BigDecimal("1000000")
        private val UNLIMITED_ALLOWANCE = BigInteger("115792089237316195423570985008687907853269984665640564039457584007913129639935")
    }

    data class ExecutionReadinessSummary(
        val status: String,
        val message: String,
        val enabledCopyTradingCount: Int = 0,
        val unhealthyCopyTradingCount: Int = 0,
        val unhealthyAccountCount: Int = 0
    )

    data class EnabledAccountDiagnostics(
        val accountId: Long,
        val accountName: String,
        val enabledCopyTradingCount: Int,
        val setupStatus: AccountSetupStatusDto
    )

    private data class CachedDiagnostics(
        val value: AccountSetupStatusDto,
        val cachedAt: Long
    )

    private val logger = LoggerFactory.getLogger(AccountExecutionDiagnosticsService::class.java)
    private val cache = ConcurrentHashMap<Long, CachedDiagnostics>()
    private val refreshScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val refreshJobs = ConcurrentHashMap<Long, Deferred<AccountSetupStatusDto>>()

    suspend fun diagnoseAccount(accountId: Long, forceRefresh: Boolean = true): Result<AccountSetupStatusDto> {
        if (accountId <= 0) {
            return Result.failure(IllegalArgumentException("账户 ID 无效"))
        }
        val account = accountRepository.findById(accountId).orElse(null)
            ?: return Result.failure(IllegalArgumentException("账户不存在"))
        return Result.success(diagnoseAccount(account, forceRefresh))
    }

    suspend fun diagnoseAccount(account: Account, forceRefresh: Boolean = false): AccountSetupStatusDto {
        val accountId = account.id
        if (!forceRefresh && accountId != null) {
            val cached = cache[accountId]
            if (cached != null && System.currentTimeMillis() - cached.cachedAt < CACHE_TTL_MS) {
                return cached.value
            }
        }

        val diagnostics = buildDiagnostics(account)
        if (accountId != null) {
            cache[accountId] = CachedDiagnostics(diagnostics, System.currentTimeMillis())
        }
        return diagnostics
    }

    /**
     * 下单热路径只做本地硬校验，避免把慢速链上诊断阻塞在每次下单前。
     * 完整链上诊断会在后台刷新；若近期已有负缓存，仍会继续拦截，防止明显异常账号继续下单。
     */
    suspend fun diagnoseAccountForExecutionHotPath(account: Account): AccountSetupStatusDto {
        val accountId = account.id
        val now = System.currentTimeMillis()
        val cached = accountId?.let { cache[it] }
        if (cached != null && now - cached.cachedAt < CACHE_TTL_MS) {
            return cached.value
        }

        val cacheAgeMs = cached?.let { now - it.cachedAt }
        if (cached != null && cached.value.executionReady && cacheAgeMs != null &&
            cacheAgeMs < HOT_PATH_STALE_SUCCESS_CACHE_TTL_MS) {
            scheduleAsyncRefresh(account)
            return cached.value
        }

        val shouldHonorCachedFailure = cached != null && !cached.value.executionReady &&
            cacheAgeMs != null && cacheAgeMs < HOT_PATH_RECENT_FAILURE_CACHE_TTL_MS

        val diagnostics = buildHotPathDiagnostics(
            account = account,
            cachedDiagnostics = cached?.value,
            cachedAt = cached?.cachedAt,
            honorCachedChainFailure = shouldHonorCachedFailure,
            now = now
        )
        scheduleAsyncRefresh(account)
        return diagnostics
    }

    fun invalidate(accountId: Long? = null) {
        if (accountId == null) {
            cache.clear()
            refreshJobs.clear()
        } else {
            cache.remove(accountId)
            refreshJobs.remove(accountId)
        }
    }

    suspend fun diagnoseEnabledCopyTradingAccounts(forceRefresh: Boolean = false): List<EnabledAccountDiagnostics> {
        val enabledCopyTradings = copyTradingRepository.findByEnabledTrue()
        if (enabledCopyTradings.isEmpty()) {
            return emptyList()
        }

        return enabledCopyTradings
            .groupBy { it.accountId }
            .mapNotNull { (accountId, copyTradings) ->
                val account = accountRepository.findById(accountId).orElse(null) ?: return@mapNotNull null
                EnabledAccountDiagnostics(
                    accountId = accountId,
                    accountName = account.accountName ?: "账户#$accountId",
                    enabledCopyTradingCount = copyTradings.size,
                    setupStatus = diagnoseAccount(account, forceRefresh = forceRefresh)
                )
            }
    }

    suspend fun summarizeEnabledCopyTradingReadiness(): ExecutionReadinessSummary {
        val diagnostics = diagnoseEnabledCopyTradingAccounts(forceRefresh = false)
        if (diagnostics.isEmpty()) {
            return ExecutionReadinessSummary(
                status = "skipped",
                message = "当前没有启用的跟单配置"
            )
        }

        val unhealthyAccounts = diagnostics.filter { !it.setupStatus.executionReady }
        val unhealthyCopyTradings = unhealthyAccounts.sumOf { it.enabledCopyTradingCount }
        val enabledCopyTradingCount = diagnostics.sumOf { it.enabledCopyTradingCount }

        return if (unhealthyAccounts.isEmpty()) {
            ExecutionReadinessSummary(
                status = "success",
                message = "所有启用的跟单配置都通过执行前诊断",
                enabledCopyTradingCount = enabledCopyTradingCount
            )
        } else {
            val sampleNames = unhealthyAccounts.take(3).map { it.accountName }
            ExecutionReadinessSummary(
                status = "error",
                message = "发现 ${unhealthyAccounts.size} 个账户、${unhealthyCopyTradings} 个启用配置存在执行前风险：${sampleNames.joinToString("、")}",
                enabledCopyTradingCount = enabledCopyTradingCount,
                unhealthyCopyTradingCount = unhealthyCopyTradings,
                unhealthyAccountCount = unhealthyAccounts.size
            )
        }
    }

    private suspend fun buildDiagnostics(account: Account): AccountSetupStatusDto {
        val checks = mutableListOf<AccountExecutionCheckDto>()
        val errors = mutableListOf<String>()

        fun addCheck(
            code: String,
            title: String,
            status: String,
            message: String,
            detail: String? = null,
            suggestion: String? = null
        ) {
            checks += AccountExecutionCheckDto(
                code = code,
                title = title,
                status = status,
                message = message,
                detail = detail,
                suggestion = suggestion
            )
            if (status == "error") {
                errors += message
            }
        }

        val walletType = WalletType.fromStringOrDefault(account.walletType, WalletType.MAGIC)
        val walletAddressValid = isValidEthereumAddress(account.walletAddress)
        addCheck(
            code = "WALLET_ADDRESS",
            title = "钱包地址",
            status = if (walletAddressValid) "success" else "error",
            message = if (walletAddressValid) "钱包地址格式正确" else "钱包地址格式不合法",
            suggestion = if (walletAddressValid) null else "检查账户钱包地址是否为 0x 开头的 42 位 EVM 地址，并与导入私钥对应。"
        )

        val proxyAddressValid = isValidEthereumAddress(account.proxyAddress)
        addCheck(
            code = "PROXY_ADDRESS",
            title = "代理钱包地址",
            status = if (proxyAddressValid) "success" else "error",
            message = if (proxyAddressValid) "代理钱包地址格式正确" else "代理钱包地址格式不合法",
            suggestion = if (proxyAddressValid) null else "重新执行代理地址检查，确认钱包类型选择正确，并保存链上推导得到的代理地址。"
        )

        addCheck(
            code = "ACCOUNT_ENABLED",
            title = "账户启用状态",
            status = if (account.isEnabled) "success" else "error",
            message = if (account.isEnabled) "账户已启用" else "账户已禁用，无法执行跟单",
            suggestion = if (account.isEnabled) null else "先启用账户，再启动或启用关联的跟单配置。"
        )

        var decryptedPrivateKey: String? = null
        var privateKeyMatchesWallet = false
        try {
            decryptedPrivateKey = cryptoUtils.decrypt(account.privateKey)
            val derivedAddress = Credentials.create(decryptedPrivateKey).address
            privateKeyMatchesWallet = derivedAddress.equals(account.walletAddress, ignoreCase = true)
            addCheck(
                code = "PRIVATE_KEY_MATCH",
                title = "私钥与钱包地址",
                status = if (privateKeyMatchesWallet) "success" else "error",
                message = if (privateKeyMatchesWallet) {
                    "私钥与钱包地址匹配"
                } else {
                    "私钥与钱包地址不匹配"
                },
                detail = if (privateKeyMatchesWallet) null else "推导地址: $derivedAddress",
                suggestion = if (privateKeyMatchesWallet) null else "重新导入正确私钥，或修正账户钱包地址为私钥实际推导出的地址。"
            )
        } catch (e: Exception) {
            logger.warn("解密账户私钥失败: accountId={}", account.id, e)
            addCheck(
                code = "PRIVATE_KEY_MATCH",
                title = "私钥与钱包地址",
                status = "error",
                message = "账户私钥无法解密或格式不合法",
                suggestion = "重新保存账户私钥，确认密钥格式正确且未被截断。"
            )
        }

        val apiCredentialsConfigured = !account.apiKey.isNullOrBlank() &&
            !account.apiSecret.isNullOrBlank() &&
            !account.apiPassphrase.isNullOrBlank()
        addCheck(
            code = "API_CREDENTIALS_CONFIGURED",
            title = "API 凭证完整性",
            status = if (apiCredentialsConfigured) "success" else "error",
            message = if (apiCredentialsConfigured) "API Key / Secret / Passphrase 已配置" else "API 凭证不完整",
            suggestion = if (apiCredentialsConfigured) null else "补齐 Polymarket API Key、Secret、Passphrase 三项配置后再启用跟单。"
        )

        val apiCredentialsDecryptable = if (apiCredentialsConfigured) {
            try {
                cryptoUtils.decrypt(account.apiSecret!!)
                cryptoUtils.decrypt(account.apiPassphrase!!)
                true
            } catch (e: Exception) {
                logger.warn("解密 API 凭证失败: accountId={}", account.id, e)
                false
            }
        } else {
            false
        }
        addCheck(
            code = "API_CREDENTIALS_DECRYPTABLE",
            title = "API 凭证可用性",
            status = when {
                !apiCredentialsConfigured -> "skipped"
                apiCredentialsDecryptable -> "success"
                else -> "error"
            },
            message = when {
                !apiCredentialsConfigured -> "未配置完整 API 凭证，跳过解密检查"
                apiCredentialsDecryptable -> "API Secret / Passphrase 解密成功"
                else -> "API Secret / Passphrase 解密失败"
            },
            suggestion = when {
                !apiCredentialsConfigured -> null
                apiCredentialsDecryptable -> null
                else -> "重新录入并保存 API Secret / Passphrase，确认密文字段未被旧数据污染。"
            }
        )

        val expectedProxyAddress = if (walletAddressValid) {
            resolveExpectedProxyAddress(account.walletAddress, walletType)
        } else {
            null
        }
        val proxyAddressMatched = expectedProxyAddress?.equals(account.proxyAddress, ignoreCase = true)
        addCheck(
            code = "PROXY_RELATION",
            title = "代理钱包关系",
            status = when {
                expectedProxyAddress == null -> "warning"
                proxyAddressMatched == true -> "success"
                else -> "error"
            },
            message = when {
                expectedProxyAddress == null -> "暂时无法推导预期代理钱包地址"
                proxyAddressMatched == true -> "代理钱包与链上推导结果一致"
                else -> "代理钱包与链上推导结果不一致"
            },
            detail = expectedProxyAddress?.let { "预期代理地址: $it" },
            suggestion = when {
                expectedProxyAddress == null -> "确认当前 RPC 可用，并检查钱包类型配置是否正确。"
                proxyAddressMatched == true -> null
                else -> "将账户中的代理地址修正为链上推导结果，避免下单时使用错误代理。"
            }
        )

        val proxyDeployed = if (proxyAddressValid) {
            try {
                blockchainService.isProxyDeployed(account.proxyAddress)
            } catch (e: Exception) {
                logger.warn("检查代理部署状态失败: accountId={}", account.id, e)
                false
            }
        } else {
            false
        }
        addCheck(
            code = "PROXY_DEPLOYED",
            title = "代理部署状态",
            status = when {
                !proxyAddressValid -> "skipped"
                proxyDeployed -> "success"
                else -> "error"
            },
            message = when {
                !proxyAddressValid -> "代理地址无效，跳过部署检查"
                proxyDeployed -> "代理钱包已部署"
                else -> "代理钱包未部署"
            },
            suggestion = when {
                !proxyAddressValid -> null
                proxyDeployed -> null
                else -> "先完成代理钱包部署，再进行授权和跟单启动。"
            }
        )

        val signatureType = try {
            orderSigningService.getSignatureTypeForWalletType(account.walletType)
        } catch (e: Exception) {
            logger.warn("计算签名类型失败: accountId={}", account.id, e)
            null
        }
        addCheck(
            code = "SIGNATURE_TYPE",
            title = "签名类型",
            status = if (signatureType != null) "success" else "warning",
            message = signatureType?.let { "签名类型=$it" } ?: "未能计算签名类型",
            suggestion = if (signatureType != null) null else "检查钱包类型配置，并确认签名服务依赖的账户字段完整。"
        )

        val approvalDetails = linkedMapOf<String, String>()
        var tokensApproved = true
        if (proxyAddressValid && proxyDeployed) {
            for ((name, spender) in APPROVAL_SPENDERS) {
                try {
                    val allowance = blockchainService.getUsdcAllowance(account.proxyAddress, spender).getOrNull()
                        ?: BigInteger.ZERO
                    val displayAmount = if (allowance >= UNLIMITED_ALLOWANCE) {
                        "unlimited"
                    } else {
                        BigDecimal(allowance).divide(USDC_DECIMALS, 6, RoundingMode.DOWN).toPlainString()
                    }
                    approvalDetails[name] = displayAmount
                    val approved = allowance > BigInteger.ZERO
                    if (!approved) {
                        tokensApproved = false
                    }
                    addCheck(
                        code = "ALLOWANCE_$name",
                        title = "USDC 授权 / $name",
                        status = if (approved) "success" else "error",
                        message = if (approved) "授权正常: $displayAmount" else "未授权或授权额度为 0",
                        detail = spender,
                        suggestion = if (approved) null else "为代理钱包补充 USDC allowance，确保对 $name 合约的授权额度大于 0。"
                    )
                } catch (e: Exception) {
                    logger.warn("读取 allowance 失败: accountId={}, spender={}", account.id, spender, e)
                    tokensApproved = false
                    approvalDetails[name] = "error"
                    addCheck(
                        code = "ALLOWANCE_$name",
                        title = "USDC 授权 / $name",
                        status = "error",
                        message = "读取 allowance 失败",
                        detail = spender,
                        suggestion = "检查 RPC 可用性与代理钱包状态，确认链上授权查询可以正常返回。"
                    )
                }
            }
        } else {
            tokensApproved = false
            addCheck(
                code = "ALLOWANCE_CHECK",
                title = "USDC 授权检查",
                status = "skipped",
                message = "代理地址不可用或尚未部署，跳过 allowance 检查",
                suggestion = "先修复代理地址和部署状态，再执行授权检查。"
            )
        }

        val error = errors.firstOrNull()
        val executionReady = account.isEnabled &&
            walletAddressValid &&
            proxyAddressValid &&
            privateKeyMatchesWallet &&
            apiCredentialsConfigured &&
            apiCredentialsDecryptable &&
            proxyDeployed &&
            (proxyAddressMatched != false) &&
            tokensApproved

        return AccountSetupStatusDto(
            proxyDeployed = proxyDeployed,
            tradingEnabled = apiCredentialsConfigured && apiCredentialsDecryptable,
            tokensApproved = tokensApproved,
            executionReady = executionReady,
            accountEnabled = account.isEnabled,
            walletType = account.walletType,
            signatureType = signatureType,
            expectedProxyAddress = expectedProxyAddress,
            proxyAddressMatched = proxyAddressMatched,
            walletAddressValid = walletAddressValid,
            proxyAddressValid = proxyAddressValid,
            privateKeyMatchesWallet = privateKeyMatchesWallet,
            apiCredentialsConfigured = apiCredentialsConfigured,
            apiCredentialsDecryptable = apiCredentialsDecryptable,
            approvalDetails = approvalDetails.ifEmpty { null },
            error = error,
            checks = checks,
            checkedAt = System.currentTimeMillis()
        )
    }

    private fun scheduleAsyncRefresh(account: Account) {
        val accountId = account.id ?: return
        val cached = cache[accountId]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.cachedAt < CACHE_TTL_MS) {
            return
        }
        refreshJobs.computeIfAbsent(accountId) {
            refreshScope.async {
                try {
                    val diagnostics = buildDiagnostics(account)
                    cache[accountId] = CachedDiagnostics(diagnostics, System.currentTimeMillis())
                    diagnostics
                } catch (e: Exception) {
                    logger.warn("后台刷新账户执行诊断失败: accountId={}", accountId, e)
                    cache[accountId]?.value ?: buildHotPathDiagnostics(
                        account = account,
                        cachedDiagnostics = cached?.value,
                        cachedAt = cached?.cachedAt,
                        honorCachedChainFailure = false,
                        now = System.currentTimeMillis()
                    )
                } finally {
                    refreshJobs.remove(accountId)
                }
            }
        }
    }

    private fun buildHotPathDiagnostics(
        account: Account,
        cachedDiagnostics: AccountSetupStatusDto?,
        cachedAt: Long?,
        honorCachedChainFailure: Boolean,
        now: Long
    ): AccountSetupStatusDto {
        val checks = mutableListOf<AccountExecutionCheckDto>()
        val errors = mutableListOf<String>()

        fun addCheck(
            code: String,
            title: String,
            status: String,
            message: String,
            detail: String? = null,
            suggestion: String? = null
        ) {
            checks += AccountExecutionCheckDto(
                code = code,
                title = title,
                status = status,
                message = message,
                detail = detail,
                suggestion = suggestion
            )
            if (status == "error") {
                errors += message
            }
        }

        val walletType = WalletType.fromStringOrDefault(account.walletType, WalletType.MAGIC)
        val walletAddressValid = isValidEthereumAddress(account.walletAddress)
        addCheck(
            code = "WALLET_ADDRESS",
            title = "钱包地址",
            status = if (walletAddressValid) "success" else "error",
            message = if (walletAddressValid) "钱包地址格式正确" else "钱包地址格式不合法",
            suggestion = if (walletAddressValid) null else "检查账户钱包地址是否为 0x 开头的 42 位 EVM 地址，并与导入私钥对应。"
        )

        val proxyAddressValid = isValidEthereumAddress(account.proxyAddress)
        addCheck(
            code = "PROXY_ADDRESS",
            title = "代理钱包地址",
            status = if (proxyAddressValid) "success" else "error",
            message = if (proxyAddressValid) "代理钱包地址格式正确" else "代理钱包地址格式不合法",
            suggestion = if (proxyAddressValid) null else "重新执行代理地址检查，确认钱包类型选择正确，并保存链上推导得到的代理地址。"
        )

        addCheck(
            code = "ACCOUNT_ENABLED",
            title = "账户启用状态",
            status = if (account.isEnabled) "success" else "error",
            message = if (account.isEnabled) "账户已启用" else "账户已禁用，无法执行跟单",
            suggestion = if (account.isEnabled) null else "先启用账户，再启动或启用关联的跟单配置。"
        )

        var privateKeyMatchesWallet = false
        try {
            val decryptedPrivateKey = cryptoUtils.decrypt(account.privateKey)
            val derivedAddress = Credentials.create(decryptedPrivateKey).address
            privateKeyMatchesWallet = derivedAddress.equals(account.walletAddress, ignoreCase = true)
            addCheck(
                code = "PRIVATE_KEY_MATCH",
                title = "私钥与钱包地址",
                status = if (privateKeyMatchesWallet) "success" else "error",
                message = if (privateKeyMatchesWallet) "私钥与钱包地址匹配" else "私钥与钱包地址不匹配",
                detail = if (privateKeyMatchesWallet) null else "推导地址: $derivedAddress",
                suggestion = if (privateKeyMatchesWallet) null else "重新导入正确私钥，或修正账户钱包地址为私钥实际推导出的地址。"
            )
        } catch (e: Exception) {
            logger.warn("热路径解密账户私钥失败: accountId={}", account.id, e)
            addCheck(
                code = "PRIVATE_KEY_MATCH",
                title = "私钥与钱包地址",
                status = "error",
                message = "账户私钥无法解密或格式不合法",
                suggestion = "重新保存账户私钥，确认密钥格式正确且未被截断。"
            )
        }

        val apiCredentialsConfigured = !account.apiKey.isNullOrBlank() &&
            !account.apiSecret.isNullOrBlank() &&
            !account.apiPassphrase.isNullOrBlank()
        addCheck(
            code = "API_CREDENTIALS_CONFIGURED",
            title = "API 凭证完整性",
            status = if (apiCredentialsConfigured) "success" else "error",
            message = if (apiCredentialsConfigured) "API Key / Secret / Passphrase 已配置" else "API 凭证不完整",
            suggestion = if (apiCredentialsConfigured) null else "补齐 Polymarket API Key、Secret、Passphrase 三项配置后再启用跟单。"
        )

        val apiCredentialsDecryptable = if (apiCredentialsConfigured) {
            try {
                cryptoUtils.decrypt(account.apiSecret!!)
                cryptoUtils.decrypt(account.apiPassphrase!!)
                true
            } catch (e: Exception) {
                logger.warn("热路径解密 API 凭证失败: accountId={}", account.id, e)
                false
            }
        } else {
            false
        }
        addCheck(
            code = "API_CREDENTIALS_DECRYPTABLE",
            title = "API 凭证可用性",
            status = when {
                !apiCredentialsConfigured -> "skipped"
                apiCredentialsDecryptable -> "success"
                else -> "error"
            },
            message = when {
                !apiCredentialsConfigured -> "未配置完整 API 凭证，跳过解密检查"
                apiCredentialsDecryptable -> "API Secret / Passphrase 解密成功"
                else -> "API Secret / Passphrase 解密失败"
            },
            suggestion = when {
                !apiCredentialsConfigured -> null
                apiCredentialsDecryptable -> null
                else -> "重新录入并保存 API Secret / Passphrase，确认密文字段未被旧数据污染。"
            }
        )

        val expectedProxyAddress = when {
            !walletAddressValid -> null
            walletType == WalletType.MAGIC -> blockchainService.calculateMagicProxyAddress(account.walletAddress)
            else -> cachedDiagnostics?.expectedProxyAddress
        }
        val proxyAddressMatched = expectedProxyAddress?.equals(account.proxyAddress, ignoreCase = true)
        addCheck(
            code = "PROXY_RELATION",
            title = "代理钱包关系",
            status = when {
                expectedProxyAddress == null -> "warning"
                proxyAddressMatched == true -> "success"
                else -> "error"
            },
            message = when {
                expectedProxyAddress == null -> "热路径未实时校验代理推导关系，后台会补充完整链上检查"
                proxyAddressMatched == true -> "代理钱包与推导结果一致"
                else -> "代理钱包与推导结果不一致"
            },
            detail = expectedProxyAddress?.let { "预期代理地址: $it" },
            suggestion = when {
                expectedProxyAddress == null -> "等待后台诊断刷新，或在账户页手动执行完整诊断。"
                proxyAddressMatched == true -> null
                else -> "将账户中的代理地址修正为推导结果，避免下单时使用错误代理。"
            }
        )

        val signatureType = try {
            orderSigningService.getSignatureTypeForWalletType(account.walletType)
        } catch (e: Exception) {
            logger.warn("热路径计算签名类型失败: accountId={}", account.id, e)
            null
        }
        addCheck(
            code = "SIGNATURE_TYPE",
            title = "签名类型",
            status = if (signatureType != null) "success" else "warning",
            message = signatureType?.let { "签名类型=$it" } ?: "未能计算签名类型",
            suggestion = if (signatureType != null) null else "检查钱包类型配置，并确认签名服务依赖的账户字段完整。"
        )

        val cachedAgeSeconds = cachedAt?.let { ((now - it).coerceAtLeast(0)) / 1000 }
        if (cachedDiagnostics != null && honorCachedChainFailure) {
            addCheck(
                code = "CHAIN_PRECHECK_CACHE",
                title = "链上执行前检查缓存",
                status = if (cachedDiagnostics.executionReady) "success" else "error",
                message = if (cachedDiagnostics.executionReady) {
                    "沿用 ${cachedAgeSeconds ?: 0}s 前的链上诊断缓存"
                } else {
                    "沿用 ${cachedAgeSeconds ?: 0}s 前的失败缓存，继续阻止下单"
                },
                suggestion = if (cachedDiagnostics.executionReady) null else "检查代理部署、授权额度或 RPC 可用性，待后台刷新后再观察。"
            )
        } else {
            addCheck(
                code = "CHAIN_PRECHECK_DEFERRED",
                title = "链上执行前检查",
                status = "warning",
                message = if (cachedDiagnostics != null) {
                    "热路径跳过实时链上诊断，先继续执行并在后台刷新缓存"
                } else {
                    "热路径首次命中该账户，实时链上诊断已转为后台刷新"
                },
                suggestion = "如需确认代理部署和 allowance 详情，可在账户页手动执行完整诊断。"
            )
        }

        val proxyDeployed = when {
            honorCachedChainFailure -> cachedDiagnostics?.proxyDeployed ?: false
            else -> cachedDiagnostics?.proxyDeployed ?: true
        }
        val tokensApproved = when {
            honorCachedChainFailure -> cachedDiagnostics?.tokensApproved ?: false
            else -> cachedDiagnostics?.tokensApproved ?: true
        }
        val approvalDetails = if (honorCachedChainFailure) {
            cachedDiagnostics?.approvalDetails
        } else {
            cachedDiagnostics?.approvalDetails?.takeIf { it.isNotEmpty() }
        }

        val executionReady = account.isEnabled &&
            walletAddressValid &&
            proxyAddressValid &&
            privateKeyMatchesWallet &&
            apiCredentialsConfigured &&
            apiCredentialsDecryptable &&
            (proxyAddressMatched != false) &&
            (!honorCachedChainFailure || (proxyDeployed && tokensApproved))

        return AccountSetupStatusDto(
            proxyDeployed = proxyDeployed,
            tradingEnabled = apiCredentialsConfigured && apiCredentialsDecryptable,
            tokensApproved = tokensApproved,
            executionReady = executionReady,
            accountEnabled = account.isEnabled,
            walletType = account.walletType,
            signatureType = signatureType,
            expectedProxyAddress = expectedProxyAddress,
            proxyAddressMatched = proxyAddressMatched,
            walletAddressValid = walletAddressValid,
            proxyAddressValid = proxyAddressValid,
            privateKeyMatchesWallet = privateKeyMatchesWallet,
            apiCredentialsConfigured = apiCredentialsConfigured,
            apiCredentialsDecryptable = apiCredentialsDecryptable,
            approvalDetails = approvalDetails,
            error = errors.firstOrNull(),
            checks = checks,
            checkedAt = now
        )
    }

    private suspend fun resolveExpectedProxyAddress(walletAddress: String, walletType: WalletType): String? {
        return try {
            blockchainService.getProxyAddress(walletAddress, walletType).getOrNull()
                ?: if (walletType == WalletType.MAGIC) {
                    blockchainService.calculateMagicProxyAddress(walletAddress)
                } else {
                    null
                }
        } catch (e: Exception) {
            logger.warn("推导代理地址失败: walletAddress={}, walletType={}", walletAddress, walletType, e)
            if (walletType == WalletType.MAGIC) {
                blockchainService.calculateMagicProxyAddress(walletAddress)
            } else {
                null
            }
        }
    }

    private fun isValidEthereumAddress(address: String?): Boolean {
        if (address.isNullOrBlank()) {
            return false
        }
        return address.matches(Regex("^0x[0-9a-fA-F]{40}$"))
    }
}
