export type ArticleTag = { id: number; name: string }
export type ArticleAttachment = { name: string; url: string; size: number }
export const formatFileSize = (size: number) => size < 1024 ? `${size} B` : size < 1048576 ? `${(size / 1024).toFixed(1)} KB` : `${(size / 1048576).toFixed(1)} MB`
export type ArticleSummary = {
  id: number; title: string; summary?: string; categoryName?: string
  cover?: string; viewCount: number; createTime?: string; tags: ArticleTag[]
}
export const articleDate = (value?: string) => value?.slice(0, 10) ?? ''
