import { articleUrl, escapeXml, type FeedPage } from '../utils/feed'

export default defineEventHandler(async (event) => {
  const config = useRuntimeConfig(event)
  const siteUrl = String(config.siteUrl).replace(/\/$/, '')
  const apiBase = String(config.public.apiBase).replace(/\/$/, '')
  const firstPage = await $fetch<FeedPage>(`${apiBase}/articles`, { query: { page: 1, size: 50 } })
  const pages = Math.ceil(firstPage.total / 50)
  const remaining = await Promise.all(Array.from({ length: Math.max(0, pages - 1) }, (_, index) =>
    $fetch<FeedPage>(`${apiBase}/articles`, { query: { page: index + 2, size: 50 } }),
  ))
  const articles = [firstPage, ...remaining].flatMap(page => page.records)
  const items = articles.map((article) => `<item><title>${escapeXml(article.title)}</title><link>${articleUrl(siteUrl, article.id)}</link><guid isPermaLink="true">${articleUrl(siteUrl, article.id)}</guid><description>${escapeXml(article.summary)}</description>${article.createTime ? `<pubDate>${new Date(article.createTime).toUTCString()}</pubDate>` : ''}</item>`).join('')
  setResponseHeader(event, 'content-type', 'application/rss+xml; charset=utf-8')
  return `<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>亿轨星途</title><link>${escapeXml(siteUrl)}</link><description>亿轨星途博客最新文章</description>${items}</channel></rss>`
})
