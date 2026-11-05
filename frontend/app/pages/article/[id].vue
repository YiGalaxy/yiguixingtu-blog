<template>
  <main class="page article-detail" :class="{ 'has-catalog': headings.length }">
    <NuxtLink class="back-link" to="/">← 返回首页</NuxtLink>
    <p v-if="pending" class="state">加载中…</p>
    <section v-else-if="error || !article" class="state">这篇文章不见了。<NuxtLink to="/">回到首页</NuxtLink></section>
    <template v-else>
      <article class="article-document">
        <header class="article-header">
          <div class="article-labels"><NuxtLink v-if="article.categoryName" class="article-category" :to="{ path: '/articles', query: { categoryId: article.categoryId } }">{{ article.categoryName }}</NuxtLink><NuxtLink v-for="tag in article.tags" :key="tag.id" class="article-tag" :to="{ path: '/articles', query: { tagId: tag.id } }">#{{ tag.name }}</NuxtLink></div>
          <h1>{{ article.title }}</h1><div class="article-meta"><time>{{ articleDate(article.createTime) }}</time><span>·</span><span>{{ article.viewCount }} 次浏览</span><span v-if="article.isTop" class="article-top">置顶</span></div>
          <img v-if="article.cover" class="article-cover" :src="article.cover" :alt="article.title">
        </header>
        <BlogMarkdown :key="article.id" :id="`article-${article.id}`" :content="article.content || ''" @catalog="headings = $event" />
        <section v-if="article.attachments?.length" class="article-attachments"><h2>附件（{{ article.attachments.length }}）</h2><ul><li v-for="(attachment, index) in article.attachments" :key="index"><a :href="attachment.url" :download="attachment.name" target="_blank" rel="noopener noreferrer"><span>{{ attachment.name }}</span><small>{{ formatFileSize(attachment.size) }}</small></a></li></ul></section>
      </article>
      <ArticleCatalog v-if="headings.length" :headings="headings" />
      <footer class="article-end"><span>── 完 ──</span><NuxtLink class="quiet-button" to="/">回到首页</NuxtLink></footer>
      <section id="comments" class="comments" aria-labelledby="comments-title">
        <h2 id="comments-title">评论<span v-if="commentPage?.total">{{ commentPage.total }}</span></h2>
        <p v-if="commentsPending && !comments.length" class="comment-empty">评论加载中…</p>
        <p v-else-if="commentsError" class="comment-empty">评论暂时读不到。<button class="quiet-button" @click="refreshComments()">重试</button></p>
        <div v-else class="comment-list"><article v-for="comment in comments" :key="comment.id" class="comment-item"><header><strong>{{ comment.nickname }}</strong><time>{{ commentTime(comment.createTime) }}</time></header><p>{{ comment.content }}</p></article><p v-if="!comments.length" class="comment-empty">还没有评论，来说点什么</p></div>
        <div v-if="comments.length < (commentPage?.total || 0)" class="comment-more"><button class="quiet-button" :disabled="loadingMore" @click="loadMoreComments">{{ loadingMore ? '加载中…' : '加载更多评论' }}</button></div>
        <p v-if="loadError" class="comment-alert error" role="alert">{{ loadError }}</p>
        <form v-if="commentEnabled" class="comment-form" @submit.prevent="submitComment">
          <h3>发表评论</h3><div class="comment-fields"><label>昵称 <span>*</span><input v-model="commentForm.nickname" maxlength="50" required placeholder="怎么称呼你"></label><label>邮箱 <em>选填</em><input v-model="commentForm.email" type="email" maxlength="200" placeholder="不会公开，只用于回复你"></label></div>
          <textarea v-model="commentForm.content" maxlength="1000" rows="4" required aria-label="评论内容" placeholder="说点什么…（最多 1000 字）" />
          <div class="comment-submit"><span>{{ commentForm.content.length }} / 1000</span><button class="comment-submit-button" type="submit" :disabled="submitting">{{ submitting ? '提交中…' : '发表评论' }}</button></div>
          <p v-if="commentMessage" class="comment-alert" :class="{ error: submitFailed }" role="status">{{ commentMessage }}</p>
        </form><p v-else class="comment-empty">评论已关闭，暂时不能发表新的评论。</p>
        <section v-if="myPending.length" class="comment-mine"><h3>你的评论（仅你可见）</h3><article v-for="comment in myPending" :key="comment.id" class="comment-item"><header><strong>{{ comment.nickname }}</strong><span>待审核</span><time>{{ commentTime(comment.createTime) }}</time></header><p>{{ comment.content }}</p></article></section>
      </section>
    </template>
  </main>
</template>
<script setup lang="ts">
import { renderMarkdown, type ArticleHeading } from '~/utils/markdown'
import { articleDate, formatFileSize, type ArticleTag, type ArticleAttachment } from '~/utils/articleTypes'
import type { SiteSettings } from '~/utils/siteTypes'
type Article = { id: number; title: string; summary?: string; content: string; cover?: string; categoryId?: number; categoryName?: string; createTime?: string; viewCount: number; tags: ArticleTag[]; isTop: number; attachments: ArticleAttachment[] }
type Comment = { id: number; nickname: string; content: string; createTime: string }
type CommentPage = { records: Comment[]; total: number }
const route = useRoute()
const config = useRuntimeConfig()
const url = computed(() => `${config.public.apiBase}/articles/${route.params.id}`)
const { data: article, pending, error } = await useFetch<Article>(url)
const { data: settings } = await useFetch<SiteSettings>(`${config.public.apiBase}/site-settings`)
const { data: commentPage, pending: commentsPending, error: commentsError, refresh: refreshComments } = await useFetch<CommentPage>(() => `${url.value}/comments/page`, { query: { size: 10 } })
const headings = ref<ArticleHeading[]>([])
watch(article, value => { headings.value = renderMarkdown(value?.content || '', true).headings }, { immediate: true })
const extraComments = ref<Comment[]>([])
const comments = computed(() => [...(commentPage.value?.records || []), ...extraComments.value])
const myPending = ref<Comment[]>([])
const commentEnabled = computed(() => settings.value?.commentEnabled ?? true)
const commentTime = (value: string) => value.replace('T', ' ').slice(0, 16)
const loadingMore = ref(false)
const loadError = ref('')
const commentForm = reactive({ nickname: '', email: '', content: '' })
const commentMessage = ref('')
const submitFailed = ref(false)
const submitting = ref(false)
let nextCommentPage = 2
const loadMoreComments = async () => {
  if (loadingMore.value) return
  const currentUrl = url.value
  loadingMore.value = true
  loadError.value = ''
  try {
    const result = await $fetch<CommentPage>(`${currentUrl}/comments/page`, { query: { page: nextCommentPage, size: 10 } })
    if (url.value !== currentUrl) return
    const existing = new Set(comments.value.map(comment => comment.id))
    extraComments.value.push(...result.records.filter(comment => !existing.has(comment.id)))
    nextCommentPage++
  } catch { if (url.value === currentUrl) loadError.value = '加载失败，请重试。' }
  finally { if (url.value === currentUrl) loadingMore.value = false }
}
watch(url, () => { extraComments.value = []; myPending.value = []; nextCommentPage = 2; loadingMore.value = false; loadError.value = ''; commentMessage.value = ''; commentForm.content = '' })
watch(() => article.value?.id, id => { if (import.meta.client && id) $fetch(`${config.public.apiBase}/articles/${id}/view`, { method: 'POST' }).catch(() => {}) }, { immediate: true })
const submitComment = async () => {
  if (submitting.value) return
  submitting.value = true
  commentMessage.value = ''
  submitFailed.value = false
  const currentUrl = url.value
  const body = { nickname: commentForm.nickname.trim(), email: commentForm.email.trim() || undefined, content: commentForm.content.trim() }
  try {
    const result = await $fetch<{ id: number }>(`${currentUrl}/comments`, { method: 'POST', body })
    if (url.value !== currentUrl) return
    const now = new Date()
    const localTime = new Date(now.getTime() - now.getTimezoneOffset() * 60_000).toISOString()
    myPending.value.unshift({ id: result.id, nickname: body.nickname, content: body.content, createTime: localTime })
    commentForm.content = ''
    commentMessage.value = '留言已提交，站长审核通过后才会出现在上面的评论列表里。'
  } catch (error: any) {
    if (url.value !== currentUrl) return
    submitFailed.value = true
    commentMessage.value = error?.statusCode === 429 ? '评论提交过于频繁，请稍后再试。' : '提交失败，请稍后重试。'
  } finally { submitting.value = false }
}
useSeoMeta({ title: () => `${article.value?.title || '文章'} · 亿轨星途`, description: () => article.value?.summary || article.value?.title })
</script>
