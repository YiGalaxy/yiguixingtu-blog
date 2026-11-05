<template>
  <main class="page article-detail">
    <p v-if="pending" class="state">正在读取文章…</p>
    <section v-else-if="error || !article" class="state">文章不存在或暂不可见。</section>
    <article v-else>
      <NuxtLink class="text-link back-link" to="/articles">← 返回文章</NuxtLink>
      <header class="article-header">
        <p class="eyebrow">{{ article.categoryName || '文章' }}</p>
        <h1>{{ article.title }}</h1>
        <p v-if="article.summary" class="lead">{{ article.summary }}</p>
        <time>{{ formatDate(article.createTime) }}</time>
      </header>
      <img v-if="article.cover" class="article-cover" :src="article.cover" :alt="article.title">
      <div class="article-content" v-html="htmlContent" />
      <section class="comments" aria-labelledby="comments-title">
        <h2 id="comments-title">评论</h2>
        <form v-if="commentEnabled" class="comment-form" @submit.prevent="submitComment">
          <label>昵称<input v-model="commentForm.nickname" maxlength="50" required></label>
          <label>留言<textarea v-model="commentForm.content" maxlength="1000" rows="4" required /></label>
          <div class="comment-submit"><p v-if="commentMessage" role="status">{{ commentMessage }}</p><button type="submit" :disabled="submitting">{{ submitting ? '正在提交…' : '提交审核' }}</button></div>
        </form>
        <p v-else class="state">评论暂未开放。</p>
        <div class="comment-list">
          <article v-for="comment in comments" :key="comment.id" class="comment-item">
            <strong>{{ comment.nickname }}</strong><time>{{ formatDate(comment.createTime) }}</time><p>{{ comment.content }}</p>
          </article>
          <p v-if="!comments.length" class="state">还没有已通过的评论。</p>
        </div>
      </section>
    </article>
  </main>
</template>

<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import sanitizeHtml from 'sanitize-html'

type Article = { id: number; title: string; summary?: string; content: string; cover?: string; categoryName?: string; createTime?: string }
const route = useRoute()
const config = useRuntimeConfig()
const { data: article, pending, error } = await useFetch<Article>(`${config.public.apiBase}/articles/${route.params.id}`)
const { data: settings } = await useFetch<{ commentEnabled: boolean }>(`${config.public.apiBase}/site-settings`)
const { data: comments, refresh: refreshComments } = await useFetch<Array<{ id: number; nickname: string; content: string; createTime: string }>>(
  `${config.public.apiBase}/articles/${route.params.id}/comments`)
const commentEnabled = computed(() => settings.value?.commentEnabled ?? true)
const commentForm = reactive({ nickname: '', content: '' })
const commentMessage = ref('')
const submitting = ref(false)
onMounted(() => {
  $fetch(`${config.public.apiBase}/articles/${route.params.id}/view`, { method: 'POST' }).catch(() => {})
})
const markdown = new MarkdownIt({ html: false, linkify: true, breaks: true })
const htmlContent = computed(() => sanitizeHtml(markdown.render(article.value?.content ?? ''), {
  allowedTags: sanitizeHtml.defaults.allowedTags.concat(['img', 'h1', 'h2', 'h3', 'h4']),
  allowedAttributes: { a: ['href', 'name', 'target', 'rel'], img: ['src', 'alt', 'title', 'width', 'height'] },
  allowedSchemes: ['http', 'https', 'mailto'],
}))
const formatDate = (value?: string) => value ? new Intl.DateTimeFormat('zh-CN').format(new Date(value)) : ''
const submitComment = async () => {
  submitting.value = true
  commentMessage.value = ''
  try {
    await $fetch(`${config.public.apiBase}/articles/${route.params.id}/comments`, { method: 'POST', body: commentForm })
    commentForm.content = ''
    commentMessage.value = '留言已提交，审核通过后会显示。'
    await refreshComments()
  } catch {
    commentMessage.value = '提交失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}
if (article.value) {
  useSeoMeta({ title: `${article.value.title} · 亿轨星途`, description: article.value.summary || article.value.title })
}
</script>
