import { describe, expect, it } from 'vitest'
import { renderMarkdown, sanitizePreview } from '../app/utils/markdown'

describe('文章展示', () => {
  it('为重复的中文标题生成不同的目录锚点', () => {
    const result = renderMarkdown('# 中文\n\n## 中文\n\n```js\n# 非标题\n```', true)
    expect(result.headings).toEqual([{ id: 'heading-1', text: '中文', level: 1 }, { id: 'heading-2', text: '中文', level: 2 }])
  })
  it('保留代码高亮与图片并移除脚本和危险链接', () => {
    const html = sanitizePreview('<pre><code class="language-js"><span class="hljs-keyword">const</span></code></pre><img src="https://example.com/a.jpg" onerror="alert(1)"><script>alert(1)</script><a href="javascript:alert(1)">链接</a>')
    expect(html).toContain('hljs-keyword')
    expect(html).toContain('https://example.com/a.jpg')
    expect(html).not.toMatch(/onerror|<script|javascript:/)
  })
  it('保留代码块折叠容器和复制控件并限制原始表单输入', () => {
    const html = sanitizePreview('<details class="md-editor-code" open><summary class="md-editor-code-head"><span class="md-editor-copy-button" data-tips="复制代码">复制代码</span></summary><pre><code>const a = 1</code></pre></details><input type="text" value="伪造表单" onfocus="alert(1)">')
    expect(html).toContain('<details class="md-editor-code" open>')
    expect(html).toContain('<summary class="md-editor-code-head">')
    expect(html).toContain('data-tips="复制代码"')
    expect(html).toContain('type="checkbox" disabled')
    expect(html).not.toMatch(/onfocus|伪造表单|type="text"/)
  })
})
