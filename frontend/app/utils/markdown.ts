import MarkdownIt from 'markdown-it'
import sanitizeHtml from 'sanitize-html'
export type ArticleHeading = { id: string; text: string; level: number }
export function sanitizePreview(html: string) {
  return sanitizeHtml(html, {
    allowedTags: [...sanitizeHtml.defaults.allowedTags, 'img', 'del', 'input', 'details', 'summary'],
    allowedAttributes: { '*': ['class', 'id'], a: ['href', 'name', 'target', 'rel'],
      img: ['src', 'alt', 'title', 'width', 'height'], input: ['type', 'checked', 'disabled'],
      details: ['open', 'class'], span: ['class', 'data-tips', 'data-is-icon'],
      code: ['class', 'data-line', 'language'], pre: ['class', 'data-language'], td: ['align'], th: ['align'] },
    allowedSchemes: ['http', 'https', 'mailto'],
    transformTags: {
      a: (tagName, attribs) => ({ tagName, attribs: { ...attribs, rel: 'noopener noreferrer' } }),
      input: (tagName, attribs) => ({ tagName, attribs: { type: 'checkbox', disabled: '', ...(Object.hasOwn(attribs, 'checked') ? { checked: '' } : {}) } }),
    },
  })
}
export function renderMarkdown(content: string, catalog = false) {
  const headings: ArticleHeading[] = []
  const markdown = new MarkdownIt({ html: false, linkify: true, breaks: true })
  const tokens = markdown.parse(content, {})
  if (catalog) {
    for (let i = 0; i < tokens.length; i++) {
      const token = tokens[i]!
      if (token.type !== 'heading_open') continue
      const text = tokens[i + 1]?.children?.map(t => t.content).join('') || tokens[i + 1]?.content || ''
      const id = `heading-${headings.length + 1}`
      token.attrSet('id', id)
      headings.push({ id, text, level: Number(token.tag.slice(1)) })
    }
  }
  const html = sanitizeHtml(markdown.renderer.render(tokens, markdown.options, {}), {
    allowedTags: [...sanitizeHtml.defaults.allowedTags, 'img', 'del'],
    allowedAttributes: { a: ['href', 'name', 'target', 'rel'], img: ['src', 'alt', 'title', 'width', 'height'],
      h1: ['id'], h2: ['id'], h3: ['id'], h4: ['id'], h5: ['id'], h6: ['id'], code: ['class'] },
    allowedSchemes: ['http', 'https', 'mailto'],
    transformTags: { a: (tagName, attribs) => ({ tagName, attribs: { ...attribs, rel: 'noopener noreferrer' } }) },
  })
  return { html, headings }
}
