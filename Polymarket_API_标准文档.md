# Polymarket API 标准文档（整理版）

> 文档更新时间：2026-03-25（Asia/Shanghai）
>
> 数据来源：官方文档爬取并结构化整理。以官方实时文档为准。

## 1. API 总览

- 官方 API Reference: https://docs.polymarket.com/api-reference/introduction
- 主要 API 域名：
  - `https://gamma-api.polymarket.com`（Events/Markets/Tags/Series/Comments/Sports/Search）
  - `https://data-api.polymarket.com`（仓位、交易、榜单、统计）
  - `https://clob.polymarket.com`（订单簿、行情、下单、撤单）
  - `https://bridge.polymarket.com`（桥接/充值/提现）
  - `https://relayer-v2.polymarket.com`（Relayer 交易中继）

## 2. 认证（Authentication）

- 文档：https://docs.polymarket.com/api-reference/authentication
- 认证模型：
  - 公共接口：无需认证
  - 受保护接口：需要 L1 或 L2 认证
- L1 请求头（钱包签名）:
  - `POLY_ADDRESS`
  - `POLY_SIGNATURE`
  - `POLY_TIMESTAMP`
  - `POLY_NONCE`
- L2 请求头（API Key）:
  - `POLY_ADDRESS`
  - `POLY_SIGNATURE`
  - `POLY_TIMESTAMP`
  - `POLY_API_KEY`
  - `POLY_PASSPHRASE`
  - `POLY_PROXY`（部分场景可见）
- 获取/派生 API 凭据端点：
  - `POST https://clob.polymarket.com/auth/api-key`
  - `GET https://clob.polymarket.com/auth/derive-api-key`

## 3. 速率限制（Rate Limits）

- 文档：
  - https://docs.polymarket.com/api-reference/rate-limits
  - https://docs.polymarket.com/quickstart/introduction/rate-limits
- 全局限制（General）：`5000 requests / 10s / IP`
- Gamma API：`250 requests / 10s / IP`
- Data API：
  - Market data: `750 requests / 10s / IP`
  - Ledger: `300 requests / 10s / IP`
  - Authentication: `100 requests / 10s / IP`
  - Trading: `125 requests / 10s / IP`
  - Other: `200 requests / 10s / IP`
- CLOB API（按流量类型）：
  - System-generated order flow: Burst `200/s`, Sustained `20/s`
  - Human-authenticated order flow: Burst `100/s`, Sustained `10/s`
  - Unauthenticated calls: Burst `20/s`, Sustained `2/s`

## 4. WebSocket

- 参考：https://docs.polymarket.com/market-data/websocket/overview

| Channel | URL | 文档 |
|---|---|---|
| Market Channel | `wss://ws-subscriptions-clob.polymarket.com/ws/market` | [link](https://docs.polymarket.com/api-reference/wss/market) |
| User Channel | `wss://ws-subscriptions-clob.polymarket.com/ws/user` | [link](https://docs.polymarket.com/api-reference/wss/user) |
| Sports Channel | `wss://sports-api.polymarket.com/ws` | [link](https://docs.polymarket.com/api-reference/wss/sports) |
| Live Data (补充) | `wss://ws-live-data.polymarket.com` | [link](https://docs.polymarket.com/market-data/websocket/overview) |

## 5. 快速调用示例

```bash
# 1) 列出事件（Gamma API）
curl --request GET --url https://gamma-api.polymarket.com/events

# 2) 查询订单簿（CLOB API）
curl --request GET --url https://clob.polymarket.com/book --get --data-urlencode "token_id=<TOKEN_ID>"

# 3) 查询用户当前仓位（Data API）
curl --request GET --url "https://data-api.polymarket.com/positions?user=<ADDRESS>"
```

## 6. 端点目录（自动提取）

> 说明：以下 URL 来自各 API Reference 页面中的官方 `curl --url` 示例自动提取。

### 6.1 Gamma API（市场与事件元数据）

| Method | URL | 描述 | 文档 |
|---|---|---|---|
| GET | `https://gamma-api.polymarket.com/comments/{id}` | Get comments by comment id | [link](https://docs.polymarket.com/api-reference/comments/get-comments-by-comment-id) |
| GET | `https://gamma-api.polymarket.com/comments/user_address/{user_address}` | Get comments by user address | [link](https://docs.polymarket.com/api-reference/comments/get-comments-by-user-address) |
| GET | `https://gamma-api.polymarket.com/events/{id}` | Get event by id | [link](https://docs.polymarket.com/api-reference/events/get-event-by-id) |
| GET | `https://gamma-api.polymarket.com/events/slug/{slug}` | Get event by slug | [link](https://docs.polymarket.com/api-reference/events/get-event-by-slug) |
| GET | `https://gamma-api.polymarket.com/events/{id}/tags` | Get event tags | [link](https://docs.polymarket.com/api-reference/events/get-event-tags) |
| GET | `https://gamma-api.polymarket.com/markets/{id}` | Get market by id | [link](https://docs.polymarket.com/api-reference/markets/get-market-by-id) |
| GET | `https://gamma-api.polymarket.com/markets/slug/{slug}` | Get market by slug | [link](https://docs.polymarket.com/api-reference/markets/get-market-by-slug) |
| GET | `https://gamma-api.polymarket.com/markets/{id}/tags` | Get market tags by id | [link](https://docs.polymarket.com/api-reference/markets/get-market-tags-by-id) |
| GET | `https://gamma-api.polymarket.com/public-profile` | Get public profile by wallet address | [link](https://docs.polymarket.com/api-reference/profiles/get-public-profile-by-wallet-address) |
| GET | `https://gamma-api.polymarket.com/tags/{id}/related-tags` | Get related tags (relationships) by tag id | [link](https://docs.polymarket.com/api-reference/tags/get-related-tags-relationships-by-tag-id) |
| GET | `https://gamma-api.polymarket.com/tags/slug/{slug}/related-tags` | Get related tags (relationships) by tag slug | [link](https://docs.polymarket.com/api-reference/tags/get-related-tags-relationships-by-tag-slug) |
| GET | `https://gamma-api.polymarket.com/series/{id}` | Get series by id | [link](https://docs.polymarket.com/api-reference/series/get-series-by-id) |
| GET | `https://gamma-api.polymarket.com/sports` | Get sports metadata information | [link](https://docs.polymarket.com/api-reference/sports/get-sports-metadata-information) |
| GET | `https://gamma-api.polymarket.com/tags/{id}` | Get tag by id | [link](https://docs.polymarket.com/api-reference/tags/get-tag-by-id) |
| GET | `https://gamma-api.polymarket.com/tags/slug/{slug}` | Get tag by slug | [link](https://docs.polymarket.com/api-reference/tags/get-tag-by-slug) |
| GET | `https://gamma-api.polymarket.com/tags/{id}/related-tags/tags` | Get tags related to a tag id | [link](https://docs.polymarket.com/api-reference/tags/get-tags-related-to-a-tag-id) |
| GET | `https://gamma-api.polymarket.com/tags/slug/{slug}/related-tags/tags` | Get tags related to a tag slug | [link](https://docs.polymarket.com/api-reference/tags/get-tags-related-to-a-tag-slug) |
| GET | `https://gamma-api.polymarket.com/sports/market-types` | Get valid sports market types | [link](https://docs.polymarket.com/api-reference/sports/get-valid-sports-market-types) |
| GET | `https://gamma-api.polymarket.com/comments` | List comments | [link](https://docs.polymarket.com/api-reference/comments/list-comments) |
| GET | `https://gamma-api.polymarket.com/events` | List events | [link](https://docs.polymarket.com/api-reference/events/list-events) |
| GET | `https://gamma-api.polymarket.com/markets` | List markets | [link](https://docs.polymarket.com/api-reference/markets/list-markets) |
| GET | `https://gamma-api.polymarket.com/series` | List series | [link](https://docs.polymarket.com/api-reference/series/list-series) |
| GET | `https://gamma-api.polymarket.com/tags` | List tags | [link](https://docs.polymarket.com/api-reference/tags/list-tags) |
| GET | `https://gamma-api.polymarket.com/teams` | List teams | [link](https://docs.polymarket.com/api-reference/sports/list-teams) |
| GET | `https://gamma-api.polymarket.com/public-search` | Search markets, events, and profiles | [link](https://docs.polymarket.com/api-reference/search/search-markets-events-and-profiles) |

### 6.2 Data API（账户/仓位/统计数据）

| Method | URL | 描述 | 文档 |
|---|---|---|---|
| GET | `https://data-api.polymarket.com/v1/accounting/snapshot` | Download an accounting snapshot (ZIP of CSVs) | [link](https://docs.polymarket.com/api-reference/misc/download-an-accounting-snapshot-zip-of-csvs) |
| GET | `https://data-api.polymarket.com/v1/builders/leaderboard` | Get aggregated builder leaderboard | [link](https://docs.polymarket.com/api-reference/builders/get-aggregated-builder-leaderboard) |
| GET | `https://data-api.polymarket.com/closed-positions` | Get closed positions for a user | [link](https://docs.polymarket.com/api-reference/core/get-closed-positions-for-a-user) |
| GET | `https://data-api.polymarket.com/positions` | Get current positions for a user | [link](https://docs.polymarket.com/api-reference/core/get-current-positions-for-a-user) |
| GET | `https://data-api.polymarket.com/v1/builders/volume` | Get daily builder volume time-series | [link](https://docs.polymarket.com/api-reference/builders/get-daily-builder-volume-time-series) |
| GET | `https://data-api.polymarket.com/live-volume` | Get live volume for an event | [link](https://docs.polymarket.com/api-reference/misc/get-live-volume-for-an-event) |
| GET | `https://data-api.polymarket.com/oi` | Get open interest | [link](https://docs.polymarket.com/api-reference/misc/get-open-interest) |
| GET | `https://data-api.polymarket.com/v1/market-positions` | Get positions for a market | [link](https://docs.polymarket.com/api-reference/core/get-positions-for-a-market) |
| GET | `https://data-api.polymarket.com/holders` | Get top holders for markets | [link](https://docs.polymarket.com/api-reference/core/get-top-holders-for-markets) |
| GET | `https://data-api.polymarket.com/traded` | Get total markets a user has traded | [link](https://docs.polymarket.com/api-reference/misc/get-total-markets-a-user-has-traded) |
| GET | `https://data-api.polymarket.com/value` | Get total value of a user's positions | [link](https://docs.polymarket.com/api-reference/core/get-total-value-of-a-users-positions) |
| GET | `https://data-api.polymarket.com/v1/leaderboard` | Get trader leaderboard rankings | [link](https://docs.polymarket.com/api-reference/core/get-trader-leaderboard-rankings) |
| GET | `https://data-api.polymarket.com/trades` | Get trades for a user or markets | [link](https://docs.polymarket.com/api-reference/core/get-trades-for-a-user-or-markets) |
| GET | `https://data-api.polymarket.com/activity` | Get user activity | [link](https://docs.polymarket.com/api-reference/core/get-user-activity) |

### 6.3 CLOB API（订单簿与交易）

| Method | URL | 描述 | 文档 |
|---|---|---|---|
| DEL | `https://clob.polymarket.com/cancel-all` | Cancel all orders | [link](https://docs.polymarket.com/api-reference/trade/cancel-all-orders) |
| DEL | `https://clob.polymarket.com/orders` | Cancel multiple orders | [link](https://docs.polymarket.com/api-reference/trade/cancel-multiple-orders) |
| DEL | `https://clob.polymarket.com/cancel-market-orders` | Cancel orders for a market | [link](https://docs.polymarket.com/api-reference/trade/cancel-orders-for-a-market) |
| DEL | `https://clob.polymarket.com/order` | Cancel single order | [link](https://docs.polymarket.com/api-reference/trade/cancel-single-order) |
| GET | `https://clob.polymarket.com/builder/trades` | Get builder trades | [link](https://docs.polymarket.com/api-reference/trade/get-builder-trades) |
| GET | `https://clob.polymarket.com/rewards/markets/current` | Get current active rewards configurations | [link](https://docs.polymarket.com/api-reference/rewards/get-current-active-rewards-configurations) |
| GET | `https://clob.polymarket.com/rebates/current` | Get current rebated fees for a maker | [link](https://docs.polymarket.com/api-reference/rebates/get-current-rebated-fees-for-a-maker) |
| GET | `https://clob.polymarket.com/rewards/user` | Get earnings for user by date | [link](https://docs.polymarket.com/api-reference/rewards/get-earnings-for-user-by-date) |
| GET | `https://clob.polymarket.com/fee-rate` | Get fee rate | [link](https://docs.polymarket.com/api-reference/market-data/get-fee-rate) |
| GET | `https://clob.polymarket.com/fee-rate/{token_id}` | Get fee rate by path parameter | [link](https://docs.polymarket.com/api-reference/market-data/get-fee-rate-by-path-parameter) |
| GET | `https://clob.polymarket.com/last-trade-price` | Get last trade price | [link](https://docs.polymarket.com/api-reference/market-data/get-last-trade-price) |
| GET | `https://clob.polymarket.com/last-trades-prices` | Get last trade prices (query parameters) | [link](https://docs.polymarket.com/api-reference/market-data/get-last-trade-prices-query-parameters) |
| GET | `https://clob.polymarket.com/price` | Get market price | [link](https://docs.polymarket.com/api-reference/market-data/get-market-price) |
| GET | `https://clob.polymarket.com/prices` | Get market prices (query parameters) | [link](https://docs.polymarket.com/api-reference/market-data/get-market-prices-query-parameters) |
| GET | `https://clob.polymarket.com/midpoint` | Get midpoint price | [link](https://docs.polymarket.com/api-reference/data/get-midpoint-price) |
| GET | `https://clob.polymarket.com/midpoints` | Get midpoint prices (query parameters) | [link](https://docs.polymarket.com/api-reference/market-data/get-midpoint-prices-query-parameters) |
| GET | `https://clob.polymarket.com/rewards/markets/multi` | Get multiple markets with rewards | [link](https://docs.polymarket.com/api-reference/rewards/get-multiple-markets-with-rewards) |
| GET | `https://clob.polymarket.com/book` | Get order book | [link](https://docs.polymarket.com/api-reference/market-data/get-order-book) |
| GET | `https://clob.polymarket.com/order-scoring` | Get order scoring status | [link](https://docs.polymarket.com/api-reference/trade/get-order-scoring-status) |
| GET | `https://clob.polymarket.com/prices-history` | Get prices history | [link](https://docs.polymarket.com/api-reference/markets/get-prices-history) |
| GET | `https://clob.polymarket.com/rewards/markets/{condition_id}` | Get raw rewards for a specific market | [link](https://docs.polymarket.com/api-reference/rewards/get-raw-rewards-for-a-specific-market) |
| GET | `https://clob.polymarket.com/rewards/user/percentages` | Get reward percentages for user | [link](https://docs.polymarket.com/api-reference/rewards/get-reward-percentages-for-user) |
| GET | `https://clob.polymarket.com/sampling-markets` | Get sampling markets | [link](https://docs.polymarket.com/api-reference/markets/get-sampling-markets) |
| GET | `https://clob.polymarket.com/sampling-simplified-markets` | Get sampling simplified markets | [link](https://docs.polymarket.com/api-reference/markets/get-sampling-simplified-markets) |
| GET | `https://clob.polymarket.com/time` | Get server time | [link](https://docs.polymarket.com/api-reference/data/get-server-time) |
| GET | `https://clob.polymarket.com/simplified-markets` | Get simplified markets | [link](https://docs.polymarket.com/api-reference/markets/get-simplified-markets) |
| GET | `https://clob.polymarket.com/order/{orderID}` | Get single order by ID | [link](https://docs.polymarket.com/api-reference/trade/get-single-order-by-id) |
| GET | `https://clob.polymarket.com/spread` | Get spread | [link](https://docs.polymarket.com/api-reference/market-data/get-spread) |
| GET | `https://clob.polymarket.com/tick-size` | Get tick size | [link](https://docs.polymarket.com/api-reference/market-data/get-tick-size) |
| GET | `https://clob.polymarket.com/tick-size/{token_id}` | Get tick size by path parameter | [link](https://docs.polymarket.com/api-reference/market-data/get-tick-size-by-path-parameter) |
| GET | `https://clob.polymarket.com/rewards/user/total` | Get total earnings for user by date | [link](https://docs.polymarket.com/api-reference/rewards/get-total-earnings-for-user-by-date) |
| GET | `https://clob.polymarket.com/trades` | Get trades | [link](https://docs.polymarket.com/api-reference/trade/get-trades) |
| GET | `https://clob.polymarket.com/rewards/user/markets` | Get user earnings and markets configuration | [link](https://docs.polymarket.com/api-reference/rewards/get-user-earnings-and-markets-configuration) |
| GET | `https://clob.polymarket.com/orders` | Get user orders | [link](https://docs.polymarket.com/api-reference/trade/get-user-orders) |
| POST | `https://clob.polymarket.com/last-trades-prices` | Get last trade prices (request body) | [link](https://docs.polymarket.com/api-reference/market-data/get-last-trade-prices-request-body) |
| POST | `https://clob.polymarket.com/prices` | Get market prices (request body) | [link](https://docs.polymarket.com/api-reference/market-data/get-market-prices-request-body) |
| POST | `https://clob.polymarket.com/midpoints` | Get midpoint prices (request body) | [link](https://docs.polymarket.com/api-reference/market-data/get-midpoint-prices-request-body) |
| POST | `https://clob.polymarket.com/books` | Get order books (request body) | [link](https://docs.polymarket.com/api-reference/market-data/get-order-books-request-body) |
| POST | `https://clob.polymarket.com/spreads` | Get spreads | [link](https://docs.polymarket.com/api-reference/market-data/get-spreads) |
| POST | `https://clob.polymarket.com/order` | Post a new order | [link](https://docs.polymarket.com/api-reference/trade/post-a-new-order) |
| POST | `https://clob.polymarket.com/orders` | Post multiple orders | [link](https://docs.polymarket.com/api-reference/trade/post-multiple-orders) |
| POST | `https://clob.polymarket.com/heartbeats` | Send heartbeat | [link](https://docs.polymarket.com/api-reference/trade/send-heartbeat) |

### 6.4 Bridge API（充提与报价）

| Method | URL | 描述 | 文档 |
|---|---|---|---|
| GET | `https://bridge.polymarket.com/supported-assets` | Get supported assets | [link](https://docs.polymarket.com/api-reference/bridge/get-supported-assets) |
| GET | `https://bridge.polymarket.com/status/{address}` | Get transaction status | [link](https://docs.polymarket.com/api-reference/bridge/get-transaction-status) |
| POST | `https://bridge.polymarket.com/deposit` | Create deposit addresses | [link](https://docs.polymarket.com/api-reference/bridge/create-deposit-addresses) |
| POST | `https://bridge.polymarket.com/withdraw` | Create withdrawal addresses | [link](https://docs.polymarket.com/api-reference/bridge/create-withdrawal-addresses) |
| POST | `https://bridge.polymarket.com/quote` | Get a quote | [link](https://docs.polymarket.com/api-reference/bridge/get-a-quote) |

### 6.5 Relayer API（交易中继）

| Method | URL | 描述 | 文档 |
|---|---|---|---|
| GET | `https://relayer-v2.polymarket.com/deployed` | Check if a safe is deployed | [link](https://docs.polymarket.com/api-reference/relayer/check-if-a-safe-is-deployed) |
| GET | `https://relayer-v2.polymarket.com/transaction` | Get a transaction by ID | [link](https://docs.polymarket.com/api-reference/relayer/get-a-transaction-by-id) |
| GET | `https://relayer-v2.polymarket.com/relayer/api/keys` | Get all relayer API keys | [link](https://docs.polymarket.com/api-reference/relayer-api-keys/get-all-relayer-api-keys) |
| GET | `https://relayer-v2.polymarket.com/nonce` | Get current nonce for a user | [link](https://docs.polymarket.com/api-reference/relayer/get-current-nonce-for-a-user) |
| GET | `https://relayer-v2.polymarket.com/transactions` | Get recent transactions for a user | [link](https://docs.polymarket.com/api-reference/relayer/get-recent-transactions-for-a-user) |
| GET | `https://relayer-v2.polymarket.com/relay-payload` | Get relayer address and nonce | [link](https://docs.polymarket.com/api-reference/relayer/get-relayer-address-and-nonce) |
| POST | `https://relayer-v2.polymarket.com/submit` | Submit a transaction | [link](https://docs.polymarket.com/api-reference/relayer/submit-a-transaction) |

### 6.6 WebSocket

| Method | URL | 描述 | 文档 |
|---|---|---|---|
| WSS | - | Market Channel | [link](https://docs.polymarket.com/api-reference/wss/market) |
| WSS | - | User Channel | [link](https://docs.polymarket.com/api-reference/wss/user) |
| WSS | - | Sports Channel | [link](https://docs.polymarket.com/api-reference/wss/sports) |

## 7. 开发建议

- 先走 Gamma/Data（读请求）再接 CLOB（交易），可以降低接入复杂度。
- 交易类接口建议统一做：时间戳同步、重试退避、签名失败报警、幂等控制。
- WebSocket 用于实时推送，REST 用于补数和回放。
- 上线前按业务流量做限流压测，确保不触发 burst/sustained 限制。
