<template>
  <main class="page home-page">
    <section class="intro">
      <div class="intro-copy">
        <h1>沿着好奇心，<br><span>继续往前。</span></h1>
        <p class="lead">亿轨星途的个人记录。写技术、做项目，也留下沿途值得回看的想法。</p>
        <form class="search" role="search" @submit.prevent="searchArticles">
          <label class="sr-only" for="home-search">搜索文章</label>
          <input id="home-search" v-model="keyword" type="search" placeholder="搜索文章标题或摘要">
          <button type="submit">查找文章</button>
        </form>
        <NuxtLink class="text-link" to="/articles">进入文章索引 ↗</NuxtLink>
      </div>
      <aside class="intro-aside">
        <strong>ABOUT THIS JOURNAL</strong>
        <p>记录正在学习的事，整理已经做过的事。这里的每篇文章，都是一段可以重新出发的线索。</p>
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
    <section class="home-stats" aria-label="文章统计"><span>文章 {{ siteStats.articleCount }}</span><span>浏览 {{ siteStats.viewCount }}</span><span>分类 {{ siteStats.categoryCount }}</span></section>
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
