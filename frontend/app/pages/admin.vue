<template>
  <main class="page admin-page">
    <aside class="admin-sidebar pixel-panel">
      <div class="sidebar-brand"><img src="/assets/brand-minecraft-pixel-transparent.png" alt="">亿轨星途 · 后台</div>
      <nav class="admin-tabs" aria-label="管理模块">
        <button v-for="item in items" :key="item.id" type="button" :aria-pressed="active === item.id" @click="select(item.id)"><span class="pixel-ui-icon" :class="item.icon" aria-hidden="true" />{{ item.label }}</button>
      </nav>
    </aside>
    <div class="admin-workspace">
    <header class="admin-header">
      <div><p class="eyebrow">SITE MANAGEMENT</p><h1>站点管理</h1></div>
      <button class="quiet-button" type="button" @click="logout">退出</button>
    </header>
    <p v-if="message" class="admin-message" role="status">{{ message }}</p>

    <section v-if="active === 'articles'" class="admin-content">
      <div class="section-heading"><h2>文章</h2><button type="button" @click="newArticle">写文章</button></div>
      <form class="filter-row" @submit.prevent="searchArticles"><input v-model="articleKeyword" placeholder="搜索标题 / 摘要" aria-label="搜索文章"><select v-model="articleCategory" aria-label="筛选分类"><option value="">全部分类</option><option v-for="category in categories" :key="category.id" :value="String(category.id)">{{ category.name }}</option></select><label for="article-status">状态</label><select id="article-status" v-model="articleStatus"><option value="">全部</option><option value="0">草稿</option><option value="1">已发布</option></select><button type="submit">查询</button><button type="button" @click="resetArticleSearch">重置</button></form>
      <p v-if="loading" class="state">正在读取…</p>
      <div v-else class="admin-table">
        <div v-for="article in articles" :key="article.id" class="admin-row">
          <div><strong>{{ article.title }}</strong><p>{{ article.status ? '已发布' : '草稿' }} · {{ article.categoryName || '未分类' }}</p></div>
          <div class="row-actions"><button class="quiet-button" type="button" @click="editArticle(article.id)">编辑</button><button class="quiet-button" type="button" @click="removeArticle(article.id)">删除</button></div>
        </div>
        <p v-if="!articles.length" class="state">还没有文章。</p>
      </div>
      <ArticlePagination :total="articleTotal" :size="articleSize" :page="articlePage" :count="Math.max(1, Math.ceil(articleTotal / articleSize))" @page="changeArticlePage" @size="changeArticleSize" />
      <dialog ref="articleDialog" class="article-edit-dialog pixel-panel" aria-label="文章编辑" @close="editing = false" @cancel="editing = false">
      <form v-if="editing" class="editor" @submit.prevent="saveArticle">
        <h3>{{ form.id ? '编辑文章' : '新建文章' }}</h3>
        <label>标题<input v-model="form.title" maxlength="200" required></label>
        <label>摘要<input v-model="form.summary" maxlength="500"></label>
        <label>封面图片<input type="file" accept="image/jpeg,image/png,image/webp,image/gif" @change="uploadCover"></label>
        <label>封面地址<input v-model="form.cover" maxlength="255"></label>
        <label>分类<select v-model="form.categoryId"><option value="">未分类</option><option v-for="category in categories" :key="category.id" :value="String(category.id)">{{ category.name }}</option></select></label>
        <label>标签<select v-model="form.tagIds" multiple><option v-for="tag in tags" :key="tag.id" :value="tag.id">{{ tag.name }}</option></select></label>
        <div class="article-editor-field"><span>正文（Markdown）</span><ClientOnly><ArticleEditor v-model="form.content" @uploading="imageUploading = $event" @error="message = $event" /></ClientOnly></div>
        <fieldset class="attachment-editor"><legend>文章附件</legend><label>上传附件<input type="file" accept=".pdf,.txt,.md,.zip,.7z,.rar,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.csv" :disabled="attachmentUploading || form.attachments.length >= 20" @change="uploadAttachment"></label><p class="muted">支持文档、表格和压缩包，每个不超过 50 MB，最多 20 个。</p><ul><li v-for="(attachment, index) in form.attachments" :key="index"><input v-model="attachment.name" aria-label="附件名称" maxlength="200" required><span>{{ formatFileSize(attachment.size) }}</span><button type="button" @click="form.attachments.splice(index, 1)">移除</button></li></ul></fieldset>
        <div class="editor-actions"><label class="inline-choice"><input v-model="form.isTop" type="checkbox">置顶</label><button type="submit" :disabled="imageUploading || attachmentUploading">{{ form.status ? '保存并发布' : '保存草稿' }}</button><button class="quiet-button" type="button" @click="form.status = form.status ? 0 : 1">切换为{{ form.status ? '草稿' : '发布' }}</button><button class="quiet-button" type="button" @click="editing = false">取消</button></div>
      </form>
      </dialog>
    </section>

    <section v-else-if="active === 'settings'" class="admin-content">
      <h2>站点设置</h2>
      <form class="editor settings-editor" @submit.prevent="saveSettings">
        <label>站点名称<input v-model="settings.siteName" maxlength="100" required></label>
        <label>首页公告<textarea v-model="settings.announcement" maxlength="500" rows="3" placeholder="留空则首页不显示公告" /></label>
        <label>滚动通知<input v-model="settings.noticeText" maxlength="500"></label>
        <label>版权文字<input v-model="settings.copyright" maxlength="200"></label>
        <label>ICP备案号<input v-model="settings.icpNumber" maxlength="100"></label>
        <label>公安备案号<input v-model="settings.policeNumber" maxlength="100"></label>
        <label>首页文章条数<input v-model.number="settings.pageSize" type="number" min="1" max="50"></label>
        <label class="inline-choice"><input v-model="settings.commentEnabled" type="checkbox">开放游客评论</label>
        <button type="submit">保存设置</button>
      </form>
    </section>

    <section v-else-if="active === 'categories' || active === 'tags'" class="admin-content">
      <div class="section-heading"><h2>{{ active === 'categories' ? '分类' : '标签' }}</h2><button type="button" @click="createTaxonomy">新增</button></div>
      <div class="admin-table"><div v-for="entry in taxonomy" :key="entry.id" class="admin-row"><strong>{{ entry.name }}</strong><div class="row-actions"><button class="quiet-button" type="button" @click="renameTaxonomy(entry.id, entry.name)">重命名</button><button class="quiet-button" type="button" @click="deleteTaxonomy(entry.id)">删除</button></div></div></div>
    </section>

    <section v-else-if="active === 'comments'" class="admin-content">
      <h2>评论管理</h2>
      <div class="filter-row"><label>状态<select v-model="commentStatus" @change="select('comments')"><option value="0">待审核</option><option value="1">已通过</option><option value="2">已拒绝</option></select></label></div>
      <div class="admin-table"><div v-for="comment in comments" :key="comment.id" class="admin-row"><div><strong>{{ comment.nickname }} · {{ comment.articleTitle }}</strong><p v-if="comment.email">联系邮箱：{{ comment.email }}</p><p>{{ comment.content }}</p></div><div class="row-actions"><button v-if="commentStatus !== '1'" type="button" @click="reviewComment(comment.id, 1)">通过</button><button v-if="commentStatus !== '2'" class="quiet-button" type="button" @click="reviewComment(comment.id, 2)">拒绝</button><button class="quiet-button" type="button" @click="deleteComment(comment.id)">删除</button></div></div><p v-if="!comments.length" class="state">没有此状态的评论。</p></div>
    </section>

    <section v-else-if="['projects', 'favorites', 'links'].includes(active)" class="admin-content">
      <div class="section-heading"><h2>{{ currentLabel }}</h2><button type="button" @click="newSiteItem">新增</button></div>
      <div class="admin-table"><div v-for="item in siteItems" :key="item.id" class="admin-row"><div><strong>{{ item.title }}</strong><p>{{ item.description }}</p></div><div class="row-actions"><button class="quiet-button" type="button" @click="editSiteItem(item)">编辑</button><button class="quiet-button" type="button" @click="removeSiteItem(item.id)">删除</button></div></div><p v-if="!siteItems.length" class="state">还没有内容。</p></div>
      <form v-if="siteItemEditing" class="editor" @submit.prevent="saveSiteItem">
        <label>名称<input v-model="siteItem.title" maxlength="120" required></label>
        <label>说明<textarea v-model="siteItem.description" maxlength="1000" rows="3" /></label>
        <label>{{ active === 'projects' ? '演示地址' : '链接' }}<input v-model="siteItem.primaryUrl" maxlength="500"></label>
        <label>{{ active === 'projects' ? '代码仓库' : active === 'links' ? '头像地址' : '分组' }}<input v-model="siteItem.secondaryUrl" maxlength="500"></label>
        <label v-if="active === 'projects'">封面地址<input v-model="siteItem.cover" maxlength="500"></label>
        <label v-if="active === 'projects'">上传封面<input type="file" accept="image/jpeg,image/png,image/webp,image/gif" @change="uploadProjectCover"></label>
        <label v-if="active === 'projects'">技术栈<input v-model="siteItem.metadata" maxlength="1000"></label>
        <label>排序<input v-model.number="siteItem.sort" type="number"></label>
        <div class="editor-actions"><button type="submit">保存</button><button class="quiet-button" type="button" @click="siteItemEditing = false">取消</button></div>
      </form>
    </section>

    <section v-else-if="active === 'about'" class="admin-content">
      <h2>关于</h2><form class="editor" @submit.prevent="saveAbout">
        <label>昵称<input v-model="aboutProfile.nickname" maxlength="100"></label>
        <label>头像地址<input v-model="aboutProfile.avatar" maxlength="500"></label>
        <label>上传头像<input type="file" accept="image/jpeg,image/png,image/webp,image/gif" @change="uploadAvatar"></label>
        <label>邮箱<input v-model="aboutProfile.email" maxlength="200"></label><label>GitHub<input v-model="aboutProfile.github" maxlength="500"></label>
        <label>微信<input v-model="aboutProfile.wechat" maxlength="100"></label><label>QQ<input v-model="aboutProfile.qq" maxlength="100"></label>
        <label>自我介绍（Markdown）<textarea v-model="aboutProfile.content" rows="12" required /></label><button type="submit">保存内容</button></form>
    </section>

    <section v-else-if="active === 'overview'" class="admin-content">
      <AdminVisitOverview />
    </section>

    <section v-else-if="active === 'storage'" class="admin-content">
      <div class="section-heading"><h2>存储</h2><span>{{ storageFiles.length }} 个文件 · 已用 {{ formatSize(storageBytes) }}</span></div>
      <div class="admin-table"><div v-for="file in storageFiles" :key="file.filename" class="admin-row"><div><strong>{{ file.filename }}</strong><p>{{ formatSize(file.size) }} · {{ file.type }}</p></div><button class="quiet-button" type="button" @click="removeMedia(file.filename)">删除</button></div><p v-if="!storageFiles.length" class="state">还没有上传文件。</p></div>
    </section>

    <section v-else class="admin-content"><h2>{{ currentLabel }}</h2><p class="state">此业务模块将在后续迭代接入。</p></section>
    </div>
  </main>
</template>

<script setup lang="ts">
import { formatFileSize, type ArticleAttachment } from '~/utils/articleTypes'

type Option = { id: number; name: string }
type ArticleRow = { id: number; title: string; categoryName?: string; status: number }
const config = useRuntimeConfig()
const token = useCookie<string | null>('blog-admin-token')
const router = useRouter()
const items = [
  { id: 'overview', label: '概览', icon: 'icon-home' },
  { id: 'articles', label: '文章管理', icon: 'icon-article' },
  { id: 'tags', label: '标签管理', icon: 'icon-links' },
  { id: 'categories', label: '分类管理', icon: 'icon-article' },
  { id: 'favorites', label: '收藏管理', icon: 'icon-favorite' },
  { id: 'projects', label: '项目管理', icon: 'icon-project' },
  { id: 'links', label: '友链管理', icon: 'icon-links' },
  { id: 'about', label: '关于管理', icon: 'icon-about' },
  { id: 'comments', label: '评论管理', icon: 'icon-article' },
  { id: 'storage', label: '存储管理', icon: 'icon-article' },
  { id: 'settings', label: '设置', icon: 'icon-workbench' },
]
const active = ref('articles')
const currentLabel = computed(() => items.find(item => item.id === active.value)?.label ?? '')
const message = ref('')
const loading = ref(false)
const editing = ref(false)
const articleDialog = useTemplateRef<HTMLDialogElement>('articleDialog')
watch(editing, value => { if (!value) articleDialog.value?.close() })
const articleStatus = ref('')
const articleKeyword = ref('')
const articleCategory = ref('')
const articlePage = ref(1)
const articleSize = ref(12)
const articleTotal = ref(0)
const commentStatus = ref('0')
const articles = ref<ArticleRow[]>([])
const categories = ref<Option[]>([])
const tags = ref<Option[]>([])
const taxonomy = ref<Array<Option & { description?: string; sort?: number }>>([])
const comments = ref<Array<{ id: number; nickname: string; email?: string; articleTitle: string; content: string }>>([])
type SiteItem = { id: number; title: string; description?: string; primaryUrl?: string; secondaryUrl?: string; metadata?: string; cover?: string; sort: number }
type MediaFile = { filename: string; url: string; size: number; type: string }
const siteItems = ref<SiteItem[]>([])
const siteItemEditing = ref(false)
const siteItem = reactive({ id: 0, title: '', description: '', primaryUrl: '', secondaryUrl: '', metadata: '', cover: '', sort: 0 })
const storageFiles = ref<MediaFile[]>([])
const storageBytes = computed(() => storageFiles.value.reduce((total, file) => total + file.size, 0))
const aboutProfile = reactive({ content: '', nickname: '', avatar: '', email: '', github: '', wechat: '', qq: '' })
const form = reactive({ id: 0, title: '', summary: '', content: '', cover: '', categoryId: '', status: 0, isTop: false, tagIds: [] as number[], attachments: [] as ArticleAttachment[] })
const attachmentUploading = ref(false)
const settings = reactive({ siteName: '亿轨星途', announcement: '', pageSize: 6, commentEnabled: true, noticeText: '', copyright: '', icpNumber: '', policeNumber: '' })
const imageUploading = ref(false)

const call = <T>(path: string, options: Record<string, unknown> = {}) => $fetch<T>(path, {
  baseURL: config.public.apiBase,
  ...options,
  headers: { Authorization: `Bearer ${token.value}`, ...(options.headers as Record<string, string> | undefined) },
})

if (!token.value) await navigateTo('/login?redirect=%2Fadmin')
const loadArticles = async () => {
  loading.value = true
  try {
    const data = await call<{ records: ArticleRow[]; total: number }>('/admin/articles', { query: { page: articlePage.value, size: articleSize.value, status: articleStatus.value || undefined, keyword: articleKeyword.value.trim() || undefined, categoryId: articleCategory.value || undefined } })
    articleTotal.value = data.total
    const lastPage = Math.max(1, Math.ceil(data.total / articleSize.value))
    if (articlePage.value > lastPage) { articlePage.value = lastPage; await loadArticles(); return }
    articles.value = data.records
  }
  catch { message.value = '读取文章失败，请重新登录后重试。' }
  finally { loading.value = false }
}
const searchArticles = () => { articlePage.value = 1; return loadArticles() }
const resetArticleSearch = () => { articleKeyword.value = ''; articleCategory.value = ''; articleStatus.value = ''; return searchArticles() }
const changeArticlePage = (page: number) => { articlePage.value = page; return loadArticles() }
const changeArticleSize = (size: number) => { articleSize.value = size; return searchArticles() }
const select = async (id: string) => {
  active.value = id
  message.value = ''
  try {
    if (id === 'articles') await loadArticles()
    if (id === 'categories') { categories.value = await call<Option[]>('/categories'); taxonomy.value = await call('/admin/categories') }
    if (id === 'tags') taxonomy.value = await call('/admin/tags')
    if (id === 'settings') Object.assign(settings, await call('/site-settings'))
    if (id === 'comments') comments.value = await call('/admin/comments', { query: { status: commentStatus.value } })
    if (['projects', 'favorites', 'links'].includes(id)) siteItems.value = await call(`/admin/collections/${id}`)
    if (id === 'about') Object.assign(aboutProfile, await call('/about'))
    if (id === 'storage') storageFiles.value = await call<MediaFile[]>('/admin/uploads')
  } catch { message.value = '读取数据失败，请检查登录状态。' }
}
const resetForm = () => Object.assign(form, { id: 0, title: '', summary: '', content: '', cover: '', categoryId: '', status: 0, isTop: false, tagIds: [], attachments: [] })
const showEditor = async () => { editing.value = true; await nextTick(); articleDialog.value?.showModal() }
const newArticle = () => { resetForm(); showEditor() }
const uploadAttachment = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || attachmentUploading.value || form.attachments.length >= 20) return
  if (file.size > 50 * 1024 * 1024) { message.value = '附件不能超过 50 MB。'; input.value = ''; return }
  attachmentUploading.value = true
  const body = new FormData(); body.append('file', file)
  try {
    const result = await call<MediaFile>('/admin/attachments', { method: 'POST', body })
    form.attachments.push({ name: file.name.slice(0, 200), url: result.url, size: result.size })
    message.value = '附件已上传，保存文章后生效。'
  } catch { message.value = '附件上传失败，请检查文件格式后重试。' }
  finally { attachmentUploading.value = false; input.value = '' }
}
const editArticle = async (id: number) => {
  try { const data = await call<typeof form>(`/admin/articles/${id}`); Object.assign(form, data, { categoryId: data.categoryId ? String(data.categoryId) : '' }); await showEditor() }
  catch { message.value = '读取文章失败。' }
}
const saveArticle = async () => {
  const body = { ...form, categoryId: form.categoryId ? Number(form.categoryId) : null }
  try {
    await call(form.id ? `/admin/articles/${form.id}` : '/admin/articles', { method: form.id ? 'PUT' : 'POST', body })
    editing.value = false; message.value = '文章已保存。'; await loadArticles()
  } catch { message.value = '保存失败，请检查标题、正文和分类。' }
}
const removeArticle = async (id: number) => {
  if (!confirm('确定删除这篇文章吗？')) return
  try { await call(`/admin/articles/${id}`, { method: 'DELETE' }); await loadArticles(); message.value = '文章已删除。' }
  catch { message.value = '删除失败。' }
}
const saveSettings = async () => {
  try { Object.assign(settings, await call('/admin/site-settings', { method: 'PUT', body: settings })); message.value = '站点设置已保存。' }
  catch { message.value = '保存失败，请检查设置内容。' }
}
const taxonomyPath = () => active.value === 'categories' ? '/admin/categories' : '/admin/tags'
const createTaxonomy = async () => {
  const name = prompt(`输入${active.value === 'categories' ? '分类' : '标签'}名称`)
  if (!name?.trim()) return
  try { await call(taxonomyPath(), { method: 'POST', body: { name: name.trim(), sort: 0 } }); await select(active.value) }
  catch { message.value = '创建失败，名称可能已存在。' }
}
const renameTaxonomy = async (id: number, oldName: string) => {
  const name = prompt('输入新名称', oldName)
  if (!name?.trim()) return
  try { await call(`${taxonomyPath()}/${id}`, { method: 'PUT', body: { name: name.trim(), sort: 0 } }); await select(active.value) }
  catch { message.value = '更新失败。' }
}
const deleteTaxonomy = async (id: number) => {
  if (!confirm('确定删除此项吗？')) return
  try { await call(`${taxonomyPath()}/${id}`, { method: 'DELETE' }); await select(active.value) }
  catch { message.value = '删除失败；请先检查是否仍有文章使用此分类。' }
}
const reviewComment = async (id: number, status: number) => {
  try { await call(`/admin/comments/${id}`, { method: 'PUT', body: { status } }); await select('comments') }
  catch { message.value = '审核操作失败。' }
}
const deleteComment = async (id: number) => {
  if (!confirm('确定删除这条评论吗？')) return
  try { await call(`/admin/comments/${id}`, { method: 'DELETE' }); await select('comments') }
  catch { message.value = '删除评论失败。' }
}
const resetSiteItem = () => Object.assign(siteItem, { id: 0, title: '', description: '', primaryUrl: '', secondaryUrl: '', metadata: '', cover: '', sort: 0 })
const newSiteItem = () => { resetSiteItem(); siteItemEditing.value = true }
const editSiteItem = (item: SiteItem) => { Object.assign(siteItem, item); siteItemEditing.value = true }
const saveSiteItem = async () => {
  try { await call(siteItem.id ? `/admin/collections/${active.value}/${siteItem.id}` : `/admin/collections/${active.value}`, { method: siteItem.id ? 'PUT' : 'POST', body: siteItem }); siteItemEditing.value = false; await select(active.value); message.value = '内容已保存。' }
  catch { message.value = '保存失败，请检查链接格式。' }
}
const removeSiteItem = async (id: number) => {
  if (!confirm('确定删除此项吗？')) return
  try { await call(`/admin/collections/${active.value}/${id}`, { method: 'DELETE' }); await select(active.value) } catch { message.value = '删除失败。' }
}
const saveAbout = async () => {
  try { await call('/admin/about', { method: 'PUT', body: aboutProfile }); message.value = '关于内容已保存。' }
  catch { message.value = '保存失败。' }
}
const uploadMedia = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return null
  const body = new FormData()
  body.append('file', file)
  try {
    const result = await call<MediaFile>('/admin/uploads', { method: 'POST', body })
    if (active.value === 'storage') storageFiles.value = await call<MediaFile[]>('/admin/uploads')
    return result.url
  } catch {
    message.value = '上传失败，请检查文件类型和大小。'
    return null
  } finally {
    input.value = ''
  }
}
const uploadCover = async (event: Event) => { const url = await uploadMedia(event); if (url) form.cover = url }
const uploadProjectCover = async (event: Event) => { const url = await uploadMedia(event); if (url) siteItem.cover = url }
const uploadAvatar = async (event: Event) => { const url = await uploadMedia(event); if (url) aboutProfile.avatar = url }
const removeMedia = async (filename: string) => {
  if (!confirm('删除文件前会检查文章和站点内容引用。确定继续吗？')) return
  try { await call(`/admin/uploads/${encodeURIComponent(filename)}`, { method: 'DELETE' }); storageFiles.value = await call<MediaFile[]>('/admin/uploads') }
  catch { message.value = '删除失败；文件可能仍被内容引用。' }
}
const formatSize = (bytes: number) => bytes < 1024 * 1024 ? `${Math.ceil(bytes / 1024)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
const logout = async () => {
  try { await call('/auth/logout', { method: 'POST' }) } finally { token.value = null; await router.push('/login') }
}
onMounted(async () => {
  const options = await Promise.allSettled([call<Option[]>('/categories'), call<Option[]>('/tags')])
  if (options[0].status === 'fulfilled') categories.value = options[0].value
  if (options[1].status === 'fulfilled') tags.value = options[1].value
  await select('articles')
})
</script>
