<template>
  <main class="page home-page">
    <section class="intro">
      <div class="intro-copy">
        <div class="profile-heading">
          <img class="profile-avatar" src="/assets/avatar-catgirl-pixel.png" alt="像素风站长头像">
          <div><p class="eyebrow">PERSONAL BLOG</p><h1>亿轨星途</h1><p class="lead">在代码与星轨之间，慢慢画自己的图。</p></div>
        </div>
        <form class="search" role="search" @submit.prevent="searchArticles">
          <label class="sr-only" for="home-search">搜索文章</label>
          <span class="search-icon" aria-hidden="true">⌕</span>
          <input id="home-search" v-model="keyword" type="search" placeholder="输入关键词搜索标题 / 摘要">
          <button type="submit">搜索</button>
        </form>
        <NuxtLink class="text-link" to="/articles">浏览全部文章 →</NuxtLink>
      </div>
      <aside class="intro-aside">
        <p class="site-status-label">BLOG STATUS</p>
        <div class="home-stats" aria-label="文章统计"><div><strong>{{ siteStats.articleCount }}</strong><span>文章</span></div><div><strong>{{ siteStats.viewCount }}</strong><span>浏览</span></div><div><strong>{{ siteStats.categoryCount }}</strong><span>分类</span></div></div>
      </aside>
    </section>

    <section v-if="announcement" class="announcement" aria-label="站点公告">
      <strong>公告</strong>
      <p>{{ announcement }}</p>
    </section>

    <section class="recent" aria-labelledby="recent-title">
      <div class="section-heading">
        <div><p class="eyebrow">LATEST NOTES</p><h2 id="recent-title">近期文章</h2></div>
        <NuxtLink class="text-link" to="/articles">查看全部 →</NuxtLink>
      </div>
      <p v-if="pending" class="state">正在读取文章…</p>
      <p v-else-if="!articles.length" class="state">文章正在整理中。</p>
      <div v-else class="article-list">
        <NuxtLink v-for="article in articles.slice(0, 5)" :key="article.id" class="article-row" :to="`/article/${article.id}`">
          <span class="article-category">{{ article.categoryName || '随笔' }}</span>
          <span class="article-title">{{ article.title }}</span>
          <time>{{ formatDate(article.createTime) }}</time>
        </NuxtLink>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
const route = useRoute()
const router = useRouter()
const config = useRuntimeConfig()
const keyword = ref('')

const legacyQuery = normalizeArticleQuery(route.query)
if (hasArticleFilters(route.query)) {
  await navigateTo({ path: '/articles', query: legacyQuery }, { redirectCode: 301 })
}

const { data, pending } = await useFetch<{ records: ArticleSummary[] }>(`${config.public.apiBase}/articles`, {
  key: 'home-recent-articles',
  query: { page: 1, size: 5 },
})
const articles = computed(() => data.value?.records ?? [])
const { data: siteSettings } = await useFetch<{ announcement?: string }>(`${config.public.apiBase}/site-settings`)
const announcement = computed(() => siteSettings.value?.announcement?.trim() ?? '')
const { data: stats } = await useFetch<{ articleCount: number; viewCount: number; categoryCount: number }>(`${config.public.apiBase}/stats`)
const siteStats = computed(() => stats.value ?? { articleCount: 0, viewCount: 0, categoryCount: 0 })

type ArticleSummary = { id: number; title: string; categoryName?: string; createTime?: string }
const searchArticles = () => router.push({ path: '/articles', query: normalizeArticleQuery({ keyword: keyword.value }) })
const formatDate = (value?: string) => value ? new Intl.DateTimeFormat('zh-CN').format(new Date(value)) : ''
</script>
