const FILTER_KEYS = ['keyword', 'categoryId', 'tagId'] as const

export type ArticleQuery = Record<string, string | string[] | undefined>

export const hasArticleFilters = (query: ArticleQuery) => FILTER_KEYS.some(key => Boolean(query[key]))

export const normalizeArticleQuery = (query: ArticleQuery) => Object.fromEntries(
  FILTER_KEYS.flatMap((key) => {
    const value = query[key]
    if (typeof value === 'string' && value.trim()) return [[key, value.trim()]]
    if (Array.isArray(value) && value[0]?.trim()) return [[key, value[0].trim()]]
    return []
  }),
)
