# MagentaWallet — implementation notes

Researched with Exa on 11 November 2026.

- [EIP-6963](https://eips.ethereum.org/EIPS/eip-6963): multi-wallet discovery. Implemented with a wallet chooser; EIP-1193 legacy fallback.
- [EIP-1193](https://eips.ethereum.org/EIPS/eip-1193): provider requests, account changes and disconnect handling.
- [ethers BrowserProvider](https://docs.ethers.org/v6/api/providers/): balance reads, fee estimation and user-approved transaction signing. ethers 6.15.0 bundled locally.
- [Circle's canonical USDC addresses](https://developers.circle.com/stablecoins/usdc-contract-addresses): native USDC contracts on Ethereum and Base; six decimal places.

## Delivered behaviour

Plain HTML/CSS/JavaScript, with an optional PHP entry point serving the same application and security headers. No build, database, environment variables, external font service or application API key. Dependencies are bundled locally. All blockchain operations go through the user's connected browser wallet. Base's public RPC and Ethereum PublicNode are only supplied when a wallet needs network configuration.

Users keep funds and keys in their own wallets. MagentaWallet is a wallet connection and payment interface; it does not create a new key store, hold deposits, issue cards, bridge assets or collect identity documents. It requests no token allowances or message signatures. Transfers require explicit approval in the connected wallet. There is no platform fee or minimum; network/provider constraints still apply. Existing wallet software is required, including on mobile through its built-in browser. WalletConnect, fiat purchases and card payments are not included.

Balances are read from mainnet, never invented. Receive QR codes contain an EIP-681 network-qualified wallet URI. Payments support ETH and native USDC on the selected network. Amounts use exact integer units. Review estimates an execution fee reserve; Base's additional data fee and the final total are shown by the connected wallet. Fee changes can still cause the wallet to reject a transaction. Transaction activity is local browser history of payments submitted here, not a full blockchain index. Receipt polling updates pending payments when the same account and network are connected. An included block-explorer link provides full history.

No claim that a particular commercial operation is exempt from legal requirements is made by this architecture.

## Libraries

- ethers.js 6.15.0 — MIT, https://github.com/ethers-io/ethers.js
- qrcode-generator 1.4.4 — MIT, https://github.com/kazuhikoarase/qrcode-generator

## Validation

JavaScript syntax check and browser checks of responsive layouts, wallet connection, exact token values, payment review, user approval request and transaction history. Browser tests use an injected mock provider; no real funds were sent.

## Interface revision — reference-led wallet layout

Replaced the editorial landing-page layout with a compact dark wallet interface based on the supplied mobile wallet references. Removed flower/star motifs, promotional headlines, decorative cards and serif typography. Added persistent bottom navigation, SVG action and token icons, searchable asset rows and network-specific balances. Kept only implemented actions. No invented portfolio valuations, price changes or unsupported assets.

Research informing this revision:
- https://ethereum.org/developers/docs/design-and-ux/heuristics-for-web3/ — visible system status, scannable key information, wallet confirmation and explorer links.
- https://www.nngroup.com/articles/progressive-disclosure/ — prioritize frequent actions; disclose secondary details when needed.
- https://re.public.polimi.it/retrieve/4473c0d5-ea0a-4116-8549-5638f804557b/computers-14-00046-with-cover.pdf — task-based Web3 interfaces, familiar interaction patterns and clear state visualization.

AI-versus-human design research is mixed and depends on what is evaluated. [Design Creativity Bench](https://arxiv.org/html/2610.11539v1) reports more repetitive model-generated UIs despite high brief adherence. [Worthy et al.](https://publications.ergonomics.org.uk/uploads/How-well-can-generative-AI-design-and-evaluate-user-interfaces.pdf) found comparable pragmatic ratings in a small static-image study but poor agreement between AI and human evaluations. Neither establishes that one visual style is universally more human. The supplied references and task requirements determine this implementation's style.

## Mainnet payments, market data and responsive layouts

- Payments submit `eth_sendTransaction` to the connected real wallet with explicit sender, chain, recipient, gas allowance and exact integer-unit value or ERC-20 transfer calldata. A returned broadcast hash is saved immediately without relying on a subsequent transaction lookup. Users must approve the payment and have sufficient real funds and ETH for fees.
- Fixed balance polling invalidating open reviews. Pending receipt updates merge into current history rather than overwriting newly submitted payments.
- Added all-USDC amount selection and downloadable receiving QR SVGs.
- Public Coinbase Exchange `/products/{product_id}/stats` provides BTC, ETH and SOL USD prices and calculated 24h changes. Coinbase `/v2/prices/USDC-USD/spot` provides USDC USD valuation. Refresh every 30 seconds while visible; stale or failed prices never become fabricated values. Portfolio USD valuation is informational, using floating-point arithmetic; transfer amounts continue to use exact integer units.
- Stock/indices quotes use the official TradingView Market Overview widget in an isolated, sandboxed child page. Stock data may be delayed. This is market viewing, not securities custody or brokerage. Third-party widget scripts never run in the wallet application's JavaScript context.
- Phone (390px), tablet (820px) and desktop (1440px) layouts captured and individually inspected. Tablet uses two columns; desktop adds side navigation and a wider main workspace. No horizontal overflow at these widths.

Sources:
- https://docs.cdp.coinbase.com/api-reference/exchange-api/rest-api/products/get-product-stats
- https://docs.cdp.coinbase.com/exchange/rest-api/rate-limits
- https://www.tradingview.com/widget-docs/widgets/watchlists/market-overview/demos/stock/
- https://www.tradingview.com/widget-docs/faq/data/ — free widget stock data is delayed, not universally real-time.

Validation limitation: this execution workspace's outbound network policy denies requests to Coinbase market domains. Consequently the production market requests are implemented against documented endpoints but a successful live feed response was not verified here. TradingView availability is likewise dependent on outbound access. No real funds or user signatures were available; automated transfer checks use injected test providers solely in the external development test script, never in shipped application code. A real-money transfer has not been executed. The optional PHP wrapper was not executed because this environment has no PHP runtime.
