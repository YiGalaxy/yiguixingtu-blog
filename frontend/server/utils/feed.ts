export type FeedArticle = {
  id: number
  title: string
  summary?: string
  createTime?: string
}

export type FeedPage = { records: FeedArticle[]; total: number }

export function escapeXml(value: unknown): string {
  return String(value ?? '').replace(/[<>&'\"]/g, (character) => ({
    '<': '&lt;',
    '>': '&gt;',
    '&': '&amp;',
    "'": '&apos;',
    '\"': '&quot;',
  })[character]!)
}

export function articleUrl(siteUrl: string, id: number): string {
  return `${siteUrl.replace(/\/$/, '')}/article/${id}`
}
