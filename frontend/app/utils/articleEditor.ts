export const insertMarkdownImage = (content: string, markdown: string, start: number, end: number) => {
  const from = Math.max(0, Math.min(content.length, start))
  const to = Math.max(from, Math.min(content.length, end))
  const updated = `${content.slice(0, from)}${markdown}${content.slice(to)}`
  return { content: updated, cursor: from + markdown.length }
}
