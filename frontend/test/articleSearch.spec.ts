import { describe, expect, it } from 'vitest'
import { hasArticleFilters, normalizeArticleQuery } from '../app/utils/articleSearch'

describe('文章检索地址', () => {
  it('识别旧首页分享链接中的筛选条件', () => {
    expect(hasArticleFilters({ keyword: 'nuxt', categoryId: '3' })).toBe(true)
  })

  it('只保留已知条件并清理重复参数和空白', () => {
    expect(normalizeArticleQuery({ keyword: ['  nuxt  ', 'ignored'], categoryId: '2', extra: 'x', tagId: '' }))
      .toEqual({ keyword: 'nuxt', categoryId: '2' })
  })

  it('没有筛选条件时不改写首页地址', () => {
    expect(hasArticleFilters({ page: '2', keyword: '' })).toBe(false)
  })
})
