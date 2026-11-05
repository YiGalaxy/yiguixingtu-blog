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
  const staticPaths = ['/', '/articles', '/archive', '/projects', '/about', '/favorites', '/links']
  const urls = [...staticPaths.map(path => `${siteUrl}${path}`), ...articles.map(article => articleUrl(siteUrl, article.id))]
  setResponseHeader(event, 'content-type', 'application/xml; charset=utf-8')
  return `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${urls.map(url => `<url><loc>${escapeXml(url)}</loc></url>`).join('')}</urlset>`
})
