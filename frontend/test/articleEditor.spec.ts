import { describe, expect, it } from 'vitest'
import { insertMarkdownImage } from '../app/utils/articleEditor'

describe('insertMarkdownImage', () => {
  it('inserts Markdown at the caret and returns the next cursor position', () => {
    expect(insertMarkdownImage('开头结尾', '![图片](url)', 2, 2)).toEqual({
      content: '开头![图片](url)结尾',
      cursor: 12,
    })
  })

  it('replaces selected text and clamps stale selection positions', () => {
    expect(insertMarkdownImage('正文选中内容', '![图片](url)', 2, 4).content).toBe('正文![图片](url)内容')
    expect(insertMarkdownImage('正文', '![图片](url)', 20, 30).content).toBe('正文![图片](url)')
  })
})
