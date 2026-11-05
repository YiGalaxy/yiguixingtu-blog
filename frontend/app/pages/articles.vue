<template>
  <main class="page articles-page">
    <header class="page-heading">
      <p class="eyebrow">ARTICLE LIBRARY</p>
      <h1>文章</h1>
      <p>按关键词、分类和标签查找已发布的内容。</p>
    </header>
    <form class="search" role="search" @submit.prevent="applySearch">
      <label class="sr-only" for="article-search">搜索文章</label>
      <input id="article-search" v-model="keyword" type="search" placeholder="搜索标题或摘要">
      <button type="submit">搜索</button>
      <button v-if="isFiltered" class="quiet-button" type="button" @click="clearFilters">清除</button>
    </form>
    <div class="filter-row">
      <label for="category-filter">分类</label>
      <select id="category-filter" v-model="categoryId" @change="syncFilters">
        <option value="">全部分类</option>
        <option v-for="item in categories" :key="item.id" :value="String(item.id)">{{ item.name }}</option>
      </select>
      <label for="tag-filter">标签</label>
      <select id="tag-filter" v-model="tagId" @change="syncFilters">
        <option value="">全部标签</option>
        <option v-for="item in tags" :key="item.id" :value="String(item.id)">{{ item.name }}</option>
      </select>
    </div>
    <p v-if="pending" class="state">正在读取文章…</p>
    <p v-else-if="!articles.length" class="state">{{ isFiltered ? '没有找到符合条件的文章。' : '还没有发布文章。' }}</p>
    <div v-else class="article-list">
      <NuxtLink v-for="article in articles" :key="article.id" class="article-row" :to="`/article/${article.id}`">
        <span class="article-category">{{ article.categoryName || '随笔' }}</span>
        <span class="article-title">{{ article.title }}</span>
        <time>{{ formatDate(article.createTime) }}</time>
      </NuxtLink>
    </div>
  </main>
</template>

<script setup lang="ts">
type Article = { id: number; title: string; summary?: string; categoryName?: string; createTime?: string }
type Option = { id: number; name: string }
const route = useRoute()
const router = useRouter()
const config = useRuntimeConfig()
const keyword = ref(typeof route.query.keyword === 'string' ? route.query.keyword : '')
const categoryId = ref(typeof route.query.categoryId === 'string' ? route.query.categoryId : '')
const tagId = ref(typeof route.query.tagId === 'string' ? route.query.tagId : '')
const filters = computed(() => ({ keyword: keyword.value.trim() || undefined, categoryId: categoryId.value || undefined, tagId: tagId.value || undefined }))
const isFiltered = computed(() => Object.values(filters.value).some(Boolean))
const { data, pending, refresh } = await useFetch<{ records: Article[] }>(`${config.public.apiBase}/articles`, { query: filters })
const articles = computed(() => data.value?.records ?? [])
const { data: categoriesData } = await useFetch<Option[]>(`${config.public.apiBase}/categories`)
const { data: tagsData } = await useFetch<Option[]>(`${config.public.apiBase}/tags`)
const categories = computed(() => categoriesData.value ?? [])
const tags = computed(() => tagsData.value ?? [])

const syncFilters = async () => {
  await router.replace({ path: '/articles', query: Object.fromEntries(Object.entries(filters.value).filter(([, value]) => value)) })
  await refresh()
}
const applySearch = () => syncFilters()
const clearFilters = () => { keyword.value = ''; categoryId.value = ''; tagId.value = ''; return syncFilters() }
const formatDate = (value?: string) => value ? new Intl.DateTimeFormat('zh-CN').format(new Date(value)) : ''
</script>
