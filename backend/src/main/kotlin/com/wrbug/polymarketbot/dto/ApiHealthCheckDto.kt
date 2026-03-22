package com.wrbug.polymarketbot.dto

/**
 * API 健康检查响应
 */
data class ApiHealthCheckDto(
    val name: String,  // API 名称
    val url: String,  // API URL
    val status: String,  // success / warning / skipped / error
    val message: String,  // 状态消息
    val responseTime: Long? = null,  // 响应时间（毫秒）
    val suggestion: String? = null  // 修复建议
)

/**
 * 启动前健康检查总览
 */
data class StartupHealthSummaryDto(
    val status: String,
    val message: String,
    val checkedAt: Long,
    val totalChecks: Int,
    val successCount: Int,
    val warningCount: Int,
    val errorCount: Int,
    val enabledCopyTradingCount: Int,
    val unhealthyCopyTradingCount: Int,
    val unhealthyAccountCount: Int,
    val actionItems: List<String> = emptyList()
)

/**
 * 启动前健康检查中的异常账户
 */
data class StartupHealthAccountDto(
    val accountId: Long,
    val accountName: String,
    val enabledCopyTradingCount: Int,
    val executionReady: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val failedChecks: List<AccountExecutionCheckDto>,
    val checkedAt: Long
)

/**
 * 所有 API 健康检查响应
 */
data class ApiHealthCheckResponse(
    val apis: List<ApiHealthCheckDto>,
    val summary: StartupHealthSummaryDto? = null,
    val unhealthyAccounts: List<StartupHealthAccountDto> = emptyList()
)

