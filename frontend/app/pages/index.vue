<template>
  <main class="page home-page">
    <ArticleFilters v-model="keyword" home :category-id="categoryId" :tag-id="tagId" :categories="categories" :tags="tags" @search="search" @category="selectCategory" @tag="selectTag" />
    <section class="profile-panel pixel-panel" aria-label="站点资料">
      <div class="profile-heading"><img class="profile-avatar" :src="profile?.avatar || '/assets/avatar-catgirl-pixel.png'" alt="站长头像"><div><h1>{{ settings?.siteName || '亿轨星途' }}</h1><p class="lead">在代码与星轨之间，慢慢画自己的图。</p></div></div>
      <div class="home-stats"><div><strong>{{ stats?.articleCount ?? 0 }}</strong><span>文章</span></div><div><strong>{{ stats?.viewCount ?? 0 }}</strong><span>浏览</span></div><div><strong>{{ stats?.categoryCount ?? 0 }}</strong><span>分类</span></div></div>
    </section>
    <div v-if="settings?.announcement" class="announcement"><strong>✦</strong><p>{{ settings.announcement }}</p></div>
    <div v-if="settings?.noticeText" class="notice pixel-panel"><p>{{ settings.noticeText }}</p></div>
    <section class="waterfall-wrap" aria-labelledby="recent-title">
      <header class="list-heading home-list-heading"><div><p class="eyebrow">RECENT PAGES</p><h2 id="recent-title">{{ title }}</h2></div><p>不追热点，只保存真正参与过的思考和过程。</p><button v-if="isFiltered" class="quiet-button" type="button" @click="clear">清除筛选</button></header>
      <p v-if="pending && !articles.length" class="state">加载中…</p>
      <div v-else-if="error" class="state">文章暂时读不到。<button type="button" @click="refresh()">重试</button></div>
      <p v-else-if="!articles.length" class="state">{{ isFiltered ? '没有找到相关文章，换个关键词、分类或标签试试' : '还没有发布任何文章' }}</p>
      <div v-else class="waterfall" :aria-busy="pending"><ArticleCard v-for="(article, i) in articles" :key="article.id" :article="article" :index="i" home @tag="selectTag" /></div>
      <div v-if="articles.length < total" class="more-row"><button class="quiet-button" type="button" :disabled="pending" @click="loadMore">{{ pending ? '加载中…' : '加载更多' }}</button><NuxtLink class="text-link" to="/articles">全部文章 →</NuxtLink></div>
    </section>
    <aside class="clock-dock"><button type="button" :aria-expanded="clockOpen" aria-label="打开时钟" @click="clockOpen = !clockOpen">◷ <span>时钟</span></button></aside>
    <section v-if="clockOpen" class="clock-panel pixel-panel" :style="{ left: `${clockPosition.x}px`, top: `${clockPosition.y}px` }" aria-label="时钟"><header @pointerdown="startClockDrag">时钟<button type="button" aria-label="关闭时钟" @click="clockOpen = false">×</button></header><strong>{{ timeText }}</strong><p>{{ dateText }}</p></section>
  </main>
</template>
<script setup lang="ts">
import type { AboutProfile } from '~/utils/siteTypes'
const browser = useArticleBrowser(true)
await browser.ready
const { keyword, categoryId, tagId, settings, articles, total, pending, error, refresh, categories, tags,
  isFiltered, title, search, selectCategory, selectTag, clear, loadMore } = browser
const config = useRuntimeConfig()
const { data: profile } = await useFetch<AboutProfile>(`${config.public.apiBase}/about`)
const { data: stats } = await useFetch<{ articleCount: number; viewCount: number; categoryCount: number }>(`${config.public.apiBase}/stats`)
const clockOpen = ref(false)
const now = ref<Date | null>(null)
const timeText = computed(() => now.value?.toLocaleTimeString('zh-CN', { hour12: false }) || '--:--:--')
const dateText = computed(() => now.value?.toLocaleDateString('zh-CN', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' }) || '')
const clockPosition = reactive({ x: 90, y: 160 })
const startClockDrag = (event: PointerEvent) => {
  if ((event.target as HTMLElement).closest('button') || event.button !== 0) return
  const handle = event.currentTarget as HTMLElement
  handle.setPointerCapture(event.pointerId)
  const origin = { x: clockPosition.x, y: clockPosition.y, pointerX: event.clientX, pointerY: event.clientY }
  const move = (next: PointerEvent) => {
    clockPosition.x = Math.max(0, Math.min(innerWidth - 270, origin.x + next.clientX - origin.pointerX))
    clockPosition.y = Math.max(80, Math.min(innerHeight - 140, origin.y + next.clientY - origin.pointerY))
  }
  const stop = () => { handle.removeEventListener('pointermove', move); handle.removeEventListener('lostpointercapture', stop) }
  handle.addEventListener('pointermove', move)
  handle.addEventListener('lostpointercapture', stop, { once: true })
}
let clockTimer: ReturnType<typeof setInterval>
onMounted(() => { now.value = new Date(); clockTimer = setInterval(() => { now.value = new Date() }, 1000) })
onBeforeUnmount(() => clearInterval(clockTimer))
useSeoMeta({ title: '亿轨星途', description: '在代码与星轨之间，慢慢画自己的图。' })
</script>
