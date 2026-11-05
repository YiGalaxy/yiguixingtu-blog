import { createHash } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const api = 'https://www.yigalaxy.xin/api'
const site = 'https://www.yigalaxy.xin'
const localMediaBase = 'http://localhost:8083/api/v1/media/'
const dbContainer = 'backend-mysql-1'
const dbName = 'yiguixingtu_preview'
const mediaDir = path.join(root, 'backend', 'uploads')
const backupDir = path.join(root, 'backend', 'local-data', 'backups')
const backupPath = path.join(backupDir, `database-before-public-preview-${Date.now()}.sql`)
const imageUrls = new Map()

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', ...options })
  if (result.status !== 0) throw new Error(result.stderr || `${command} exited with ${result.status}`)
  return result.stdout
}

async function getJson(endpoint) {
  const response = await fetch(new URL(endpoint.replace(/^\/+/, ''), `${api}/`))
  if (!response.ok) throw new Error(`Public API returned ${response.status} for ${endpoint}`)
  const body = await response.json()
  if (body.code !== 200) throw new Error(`Public API rejected ${endpoint}: ${body.message || body.code}`)
  return body.data
}

function listOf(data) {
  return Array.isArray(data) ? data : Array.isArray(data?.records) ? data.records : []
}

function sql(value) {
  if (value === null || value === undefined) return 'NULL'
  if (typeof value === 'boolean') return value ? '1' : '0'
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : '0'
  return `'${String(value).replaceAll('\\', '\\\\').replaceAll("'", "''").replaceAll('\0', '')}'`
}

function timestamp(value) {
  if (!value) return null
  return String(value).replace('T', ' ').replace(/Z$/, '').replace(/\.\d+$/, '').slice(0, 19)
}

function collectImages(value) {
  if (typeof value === 'string') {
    const candidates = value.match(/(?:https?:\/\/[^\s<>"')]+|\/uploads\/[A-Za-z0-9._/-]+)/g) || []
    for (const candidate of candidates) {
      const url = new URL(candidate, site)
      if (url.origin !== site || !url.pathname.includes('/uploads/')) continue
      const extension = path.extname(url.pathname).toLowerCase()
      if (!['.jpg', '.jpeg', '.png', '.gif', '.webp'].includes(extension)) continue
      imageUrls.set(candidate, url)
    }
  } else if (Array.isArray(value)) {
    for (const item of value) collectImages(item)
  } else if (value && typeof value === 'object') {
    for (const item of Object.values(value)) collectImages(item)
  }
}

function imageFilename(url) {
  const digest = createHash('sha256').update(url.href).digest('hex')
  return `online-${digest.slice(0, 24)}${path.extname(url.pathname).toLowerCase()}`
}

async function main() {
  const articles = []
  const firstPage = await getJson('/article/page?page=1&size=50')
  articles.push(...listOf(firstPage))
  const totalPages = Math.ceil((firstPage.total || articles.length) / (firstPage.size || 50))
  for (let page = 2; page <= totalPages; page++) {
    articles.push(...listOf(await getJson(`/article/page?page=${page}&size=50`)))
  }
  const [categories, tags, settings, about, projects, favorites, links] = await Promise.all([
    getJson('/category/list'), getJson('/tag/list'), getJson('/setting'), getJson('/about'),
    getJson('/project/list'), getJson('/favorite/list'), getJson('/link/list'),
  ])
  const published = articles.filter(article => Number(article.status) === 1)
  if (published.length !== articles.length) throw new Error('The public article endpoint returned a non-public record')
  for (const value of [published, categories, tags, settings, about, projects, favorites, links]) collectImages(value)

  const backup = run('docker', ['exec', dbContainer, 'mysqldump', '-ublog', '-pblog-local-password', '--single-transaction', dbName])
  await mkdir(mediaDir, { recursive: true })
  await mkdir(backupDir, { recursive: true })
  await writeFile(backupPath, backup, 'utf8')

  const contentTables = ['article', 'article_tag', 'category', 'tag', 'project', 'favorite', 'friend_link']
  const checks = contentTables.map(table => `SELECT '${table}', COUNT(*) FROM ${table}`).join(' UNION ALL ')
  const current = run('docker', ['exec', dbContainer, 'mysql', '-ublog', '-pblog-local-password', '-N', dbName, '-e', checks])
  const occupied = current.trim().split(/\r?\n/).filter(line => Number(line.split('\t')[1]) > 0)
  if (occupied.length) throw new Error(`Local content tables are not empty (${occupied.map(line => line.split('\t')[0]).join(', ')}); backup saved at ${backupPath}`)

  const localImages = new Map()
  for (const [reference, url] of imageUrls) {
    const filename = imageFilename(url)
    const response = await fetch(url)
    if (!response.ok) throw new Error(`Could not download a referenced image (${response.status})`)
    const type = response.headers.get('content-type') || ''
    if (!type.startsWith('image/')) throw new Error('An image URL returned a non-image response')
    const bytes = Buffer.from(await response.arrayBuffer())
    if (bytes.length > 50 * 1024 * 1024) throw new Error('A referenced image exceeds the local upload limit')
    await writeFile(path.join(mediaDir, filename), bytes, { flag: 'wx' }).catch(error => {
      if (error.code !== 'EEXIST') throw error
    })
    localImages.set(reference, `${localMediaBase}${filename}`)
    localImages.set(url.href, `${localMediaBase}${filename}`)
    localImages.set(url.pathname, `${localMediaBase}${filename}`)
  }

  const localize = value => {
    let result = value || ''
    for (const [source, target] of localImages) result = result.replaceAll(source, target)
    return result
  }
  const statements = ['START TRANSACTION']
  for (const category of categories) {
    statements.push(`INSERT INTO category (id, name, description, sort) VALUES (${sql(category.id)}, ${sql(category.name)}, ${sql(category.description)}, ${sql(category.sort || 0)})`)
  }
  for (const tag of tags) {
    statements.push(`INSERT INTO tag (id, name, sort) VALUES (${sql(tag.id)}, ${sql(tag.name)}, ${sql(tag.sort || 0)})`)
  }
  for (const article of published) {
    const cover = localize(article.cover)
    statements.push(`INSERT INTO article (id, title, summary, content, cover, category_id, status, view_count, is_top, created_at, updated_at, deleted) VALUES (${sql(article.id)}, ${sql(article.title)}, ${sql(article.summary)}, ${sql(localize(article.content))}, ${sql(cover)}, ${sql(article.categoryId)}, 1, ${sql(article.viewCount || 0)}, ${sql(article.isTop)}, ${sql(timestamp(article.createTime))}, ${sql(timestamp(article.updateTime))}, 0)`)
    for (const tag of article.tags || []) {
      if (Number.isInteger(Number(tag.id))) statements.push(`INSERT INTO article_tag (article_id, tag_id) VALUES (${sql(article.id)}, ${sql(tag.id)})`)
    }
  }
  for (const item of listOf(projects)) {
    statements.push(`INSERT INTO project (id, title, description, primary_url, secondary_url, metadata, sort) VALUES (${sql(item.id)}, ${sql(item.name)}, ${sql(item.description)}, ${sql(localize(item.url))}, ${sql(item.repo)}, ${sql(Array.isArray(item.tech) ? item.tech.join(', ') : item.tech)}, ${sql(item.sort || 0)})`)
  }
  for (const item of listOf(favorites)) {
    statements.push(`INSERT INTO favorite (id, title, description, primary_url, secondary_url, metadata, sort) VALUES (${sql(item.id)}, ${sql(item.title)}, ${sql(item.description)}, ${sql(item.url)}, ${sql(item.category)}, NULL, ${sql(item.sort || 0)})`)
  }
  for (const item of listOf(links)) {
    statements.push(`INSERT INTO friend_link (id, title, description, primary_url, secondary_url, metadata, sort) VALUES (${sql(item.id)}, ${sql(item.name)}, ${sql(item.description)}, ${sql(item.url)}, ${sql(item.avatar)}, NULL, ${sql(item.sort || 0)})`)
  }

  statements.push(`INSERT IGNORE INTO admin_user (username, password_hash) SELECT username, password_hash FROM yiguixingtu_blog.admin_user WHERE username = 'admin' LIMIT 1`)
  const aboutLines = [`# ${about.nickname || '关于我'}`, '', about.bio || '']
  if (about.avatar) aboutLines.push('', `![${about.nickname || '头像'}](${localize(about.avatar)})`)
  if (about.github) aboutLines.push('', `[GitHub](${about.github})`)
  statements.push(`UPDATE about_page SET content = ${sql(aboutLines.join('\n'))} WHERE id = 1`)
  const announcement = String(settings.announcement || settings.noticeText || '').slice(0, 500) || null
  statements.push(`UPDATE site_setting SET site_name = ${sql(settings.siteName || '亿轨星途')}, announcement = ${sql(announcement)}, comment_enabled = ${sql(settings.commentEnabled)}, page_size = ${sql(settings.pageSize || 12)} WHERE id = 1`)
  statements.push('COMMIT')

  run('docker', ['exec', '-i', dbContainer, 'mysql', '--default-character-set=utf8mb4', '-ublog', '-pblog-local-password', dbName], { input: `${statements.join(';\n')};\n` })
  console.log(JSON.stringify({ articles: published.length, categories: categories.length, tags: tags.length, projects: listOf(projects).length, favorites: listOf(favorites).length, links: listOf(links).length, images: imageUrls.size, databaseBackup: backupPath }, null, 2))
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
